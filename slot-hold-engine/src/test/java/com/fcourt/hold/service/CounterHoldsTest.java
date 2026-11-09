package com.fcourt.hold.service;

import com.fcourt.hold.client.HoldStaffIdentity;
import com.fcourt.hold.controller.CounterSafetyServer;
import com.fcourt.hold.dto.request.*;
import com.fcourt.hold.dto.response.*;
import com.fcourt.hold.repository.RedisHoldStore;

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
    @Autowired OnlineHolds online;
    @Autowired RedissonClient redis;
    @MockBean HoldStaffIdentity identity;
    LocalDate day = LocalDate.of(2026, 11, 1);

    @BeforeEach
    void clear() {
        redis.getKeys().flushdb();
        reset(identity);
    }

    @Test
    void onlineClaimSealsPublicHoldAndInternalReleasePreservesOtherOwners() {
        String token=UUID.randomUUID().toString();
        var selection=new HoldRequest(21L,day,List.of(1L,2L),"0901234567");
        assertThat(publicHolds.acquire(selection,token)).isNotNull();
        var request=new OnlineHoldClaimRequest(UUID.fromString(token),200,UUID.randomUUID(),21L,day,List.of(2L,1L));
        var lease=online.claim("fixture-booking-hold-secret-not-production",request);
        assertThat(lease.guestPhone()).isEqualTo("0901234567");
        assertThat(lease.holdHash()).isEqualTo(OnlineHolds.hash(token));
        assertThat(online.claim("fixture-booking-hold-secret-not-production",request).holdHash()).isEqualTo(lease.holdHash());
        publicHolds.release(token);
        assertThat(redis.getBucket(CounterHolds.slotKey(21,day,1)).isExists()).isTrue();
        assertThatThrownBy(() -> online.claim("fixture-booking-hold-secret-not-production",
                new OnlineHoldClaimRequest(UUID.fromString(token),201,UUID.randomUUID(),21L,day,List.of(1L,2L))))
                .isInstanceOf(ResponseStatusException.class);
        redis.getBucket(CounterHolds.slotKey(21,day,2),org.redisson.client.codec.StringCodec.INSTANCE).set("another-owner");
        online.release("fixture-booking-hold-secret-not-production",lease.holdHash());
        online.release("fixture-booking-hold-secret-not-production",lease.holdHash());
        assertThat(redis.getBucket(CounterHolds.slotKey(21,day,1)).isExists()).isFalse();
        assertThat(redis.getBucket(CounterHolds.slotKey(21,day,2),org.redisson.client.codec.StringCodec.INSTANCE).get()).isEqualTo("another-owner");
    }

    @Test
    void onlineClaimRejectsMissingExpiredChangedOrUncredentialedHolds() {
        String token=UUID.randomUUID().toString();
        var request=new OnlineHoldClaimRequest(UUID.fromString(token),200,UUID.randomUUID(),21L,day,List.of(1L));
        assertThatThrownBy(() -> online.claim(null,request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> online.claim("fixture-booking-hold-secret-not-production",request)).isInstanceOf(ResponseStatusException.class);
        publicHolds.acquire(new HoldRequest(21L,day,List.of(1L,2L),"0901234567"),token);
        assertThatThrownBy(() -> online.claim("fixture-booking-hold-secret-not-production",request)).isInstanceOf(ResponseStatusException.class);
        redis.getBucket("fcourt:hold-token:"+token).expire(java.time.Duration.ofSeconds(20));
        var full=new OnlineHoldClaimRequest(UUID.fromString(token),200,UUID.randomUUID(),21L,day,List.of(1L,2L));
        assertThatThrownBy(() -> online.claim("fixture-booking-hold-secret-not-production",full)).isInstanceOf(ResponseStatusException.class);
        assertThat(redis.getBucket("fcourt:online-seal:"+OnlineHolds.hash(token)).isExists()).isFalse();
    }

    @Test
    void competingOnlineClaimsHaveOneOwner() throws Exception {
        String token=UUID.randomUUID().toString();
        publicHolds.acquire(new HoldRequest(21L,day,List.of(1L),"0901234567"),token);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            var futures=new ArrayList<Future<Boolean>>();
            for(long actor:List.of(200L,201L)) futures.add(pool.submit(() -> {
                gate.await();
                try { online.claim("fixture-booking-hold-secret-not-production",new OnlineHoldClaimRequest(UUID.fromString(token),actor,UUID.randomUUID(),21L,day,List.of(1L))); return true; }
                catch(ResponseStatusException e){return false;}
            }));
            gate.countDown();
            assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
    }

    CounterHoldRequest request(String key) {
        return new CounterHoldRequest(7, 100, 21, day, List.of(1L, 2L), key);
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
        var changed = new CounterHoldRequest(7, 100, 21, day, List.of(1L), key);
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
                new CounterHoldRequest(7, 100, 21, day, Arrays.asList(1L, null), key());
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
