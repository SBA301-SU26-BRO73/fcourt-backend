package com.fcourt.hold;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fcourt.staff.grpc.*;

import io.grpc.*;
import io.grpc.stub.MetadataUtils;

import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(
        properties = "BOOKING_HOLD_SERVICE_TOKEN=fixture-booking-hold-secret-not-production")
class CounterHoldsTest {
    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add(
                "hold.redis-address",
                () ->
                        "redis://"
                                + redisContainer.getHost()
                                + ":"
                                + redisContainer.getMappedPort(6379));
    }

    @Autowired CounterHolds holds;
    @Autowired RedisHoldStore publicHolds;
    @Autowired RedissonClient redis;
    @MockBean HoldStaffIdentity identity;
    LocalDate day = LocalDate.of(2026, 11, 1);

    @BeforeEach
    void clear() {
        redis.getKeys().flushdb();
        reset(identity);
    }

    CounterHolds.Request request(String key) {
        return new CounterHolds.Request(7, 100, 21, day, List.of(1L, 2L), key);
    }

    String key() {
        return "staff:walk-in:100:" + UUID.randomUUID();
    }

    @Test
    void counterLeaseReplaysWithoutExtendingAndCompetesWithPublicHold() {
        String key = key();
        var lease =
                holds.reserve(
                        "Bearer token", "fixture-booking-hold-secret-not-production", request(key));
        assertThat(
                        holds.reserve(
                                "Bearer token",
                                "fixture-booking-hold-secret-not-production",
                                request(key)))
                .isEqualTo(lease);
        assertThat(
                        publicHolds.acquire(
                                new HoldRequest(21L, day, List.of(2L, 3L), "0912345678"),
                                "public-token"))
                .isNull();
        assertThat(
                        redis.getBucket(
                                        CounterHolds.slotKey(21, day, 3),
                                        org.redisson.client.codec.StringCodec.INSTANCE)
                                .get())
                .isNull();
        assertThat(holds.busy(21, day, List.of(1L, 2L), key)).isEmpty();
        assertThat(holds.busy(21, day, List.of(1L, 2L), key())).containsExactly(1L, 2L);
    }

    @Test
    void serviceCredentialAndPayloadChangesFailClosed() {
        String key = key();
        assertThatThrownBy(() -> holds.reserve("Bearer token", "wrong", request(key)))
                .isInstanceOf(ResponseStatusException.class);
        holds.reserve("Bearer token", "fixture-booking-hold-secret-not-production", request(key));
        var changed = new CounterHolds.Request(7, 100, 21, day, List.of(1L), key);
        assertThatThrownBy(
                        () ->
                                holds.reserve(
                                        "Bearer token",
                                        "fixture-booking-hold-secret-not-production",
                                        changed))
                .isInstanceOf(ResponseStatusException.class);
        verify(identity, times(2)).require("Bearer token", 7, 100);
    }

    @Test
    void publicAndCounterRaceHasOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var counter =
                    pool.submit(
                            () -> {
                                gate.await();
                                try {
                                    holds.reserve(
                                            "Bearer token",
                                            "fixture-booking-hold-secret-not-production",
                                            request(key()));
                                    return true;
                                } catch (ResponseStatusException e) {
                                    return false;
                                }
                            });
            var customer =
                    pool.submit(
                            () -> {
                                gate.await();
                                return publicHolds.acquire(
                                                new HoldRequest(
                                                        21L, day, List.of(1L, 2L), "0912345678"),
                                                "public-token")
                                        != null;
                            });
            gate.countDown();
            assertThat(
                            (counter.get(10, TimeUnit.SECONDS) ? 1 : 0)
                                    + (customer.get(10, TimeUnit.SECONDS) ? 1 : 0))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void grpcRejectsMalformedDateAsInvalidArgument() throws Exception {
        var server = new CounterSafetyServer(identity, holds, "127.0.0.1", 0, "", "");
        server.start();
        var channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            var headers = new Metadata();
            headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer token");
            var request =
                    CheckSlotsRequest.newBuilder()
                            .setActorId(100)
                            .setBranchId(7)
                            .setCourtId(21)
                            .setDate("not-a-date")
                            .addAllSlotIds(List.of(1L))
                            .setCommandKey(key())
                            .build();
            var stub =
                    HoldSafetyGrpc.newBlockingStub(channel)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                            .withDeadlineAfter(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stub.check(request))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e ->
                                    assertThat(e.getStatus().getCode())
                                            .isEqualTo(Status.Code.INVALID_ARGUMENT));
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }

    @Test
    void counterRequestRejectsNullSlotId() {
        var invalid =
                new CounterHolds.Request(7, 100, 21, day, Arrays.asList(1L, null), key());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(invalid)).isNotEmpty();
        }
    }

    @Test
    void grpcRequiresBearerAndReportsRedisHolds() throws Exception {
        holds.reserve("Bearer token", "fixture-booking-hold-secret-not-production", request(key()));
        var server = new CounterSafetyServer(identity, holds, "127.0.0.1", 0, "", "");
        server.start();
        var channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            var request =
                    CheckSlotsRequest.newBuilder()
                            .setActorId(100)
                            .setBranchId(7)
                            .setCourtId(21)
                            .setDate(day.toString())
                            .addAllSlotIds(List.of(1L, 2L))
                            .setCommandKey(key())
                            .build();
            assertThatThrownBy(() -> HoldSafetyGrpc.newBlockingStub(channel).check(request))
                    .isInstanceOf(StatusRuntimeException.class);
            var headers = new Metadata();
            headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer token");
            var reply =
                    HoldSafetyGrpc.newBlockingStub(channel)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                            .withDeadlineAfter(3, TimeUnit.SECONDS)
                            .check(request);
            assertThat(reply.getComplete()).isTrue();
            assertThat(reply.getBusySlotIdsList()).containsExactly(1L, 2L);
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }
}
