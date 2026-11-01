package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fcourt.staff.grpc.BookingSafetyGrpc;
import com.fcourt.staff.grpc.CheckSlotsRequest;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;

import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class CounterSafetyServerTest {
    CounterSafetyServer server;
    ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        server =
                new CounterSafetyServer(
                        mock(StaffAuthority.class),
                        mock(StaffCounter.class),
                        mock(JwtDecoder.class),
                        "127.0.0.1",
                        0,
                        "",
                        "");
        server.start();
        channel =
                NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.stop();
    }

    BookingSafetyGrpc.BookingSafetyBlockingStub stub(String bearer) {
        var headers = new Metadata();
        if (bearer != null)
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), bearer);
        return BookingSafetyGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    CheckSlotsRequest request(String date) {
        return CheckSlotsRequest.newBuilder()
                .setBranchId(1)
                .setActorId(2)
                .setCourtId(3)
                .addSlotIds(4)
                .setDate(date)
                .build();
    }

    @Test
    void malformedDateIsInvalidArgumentNotUnavailable() {
        assertThatThrownBy(() -> stub("Bearer token").check(request("2026-13-45")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> stub("Bearer token").check(request("tomorrow")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void missingBearerIsUnauthenticated() {
        assertThatThrownBy(() -> stub(null).check(request("2026-11-02")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
