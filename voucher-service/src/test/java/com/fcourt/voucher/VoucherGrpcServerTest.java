package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fcourt.voucher.grpc.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VoucherGrpcServerTest {
    @Test
    void plaintextIsLimitedToLoopbackAndMissingTokenDeniesAllCalls() throws Exception {
        var pricing = mock(VoucherPricing.class);
        var handler = new VoucherGrpcService(pricing);
        assertThatThrownBy(() -> new VoucherGrpcServer(handler, "0.0.0.0", 0, "token", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoucherGrpcServer(handler, "127.0.0.1", 0, "token", "cert.pem", ""))
                .isInstanceOf(IllegalArgumentException.class);
        var server = new VoucherGrpcServer(handler, "127.0.0.1", 0, "", "", "");
        server.start();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            assertThat(server.isRunning()).isTrue();
            var metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER), "anything");
            var stub = VoucherValidationGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            assertThatThrownBy(() -> stub.validate(ValidateVoucherRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            verifyNoInteractions(pricing);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.stop();
        }
        assertThat(server.isRunning()).isFalse();
    }
}
