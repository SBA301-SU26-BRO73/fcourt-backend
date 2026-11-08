package com.fcourt.voucher;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "voucher.grpc.enabled", havingValue = "true", matchIfMissing = true)
class VoucherGrpcServer implements SmartLifecycle {
    private final Server server;
    private volatile boolean running;

    VoucherGrpcServer(VoucherGrpcService service, @Value("${voucher.grpc.host:127.0.0.1}") String host,
            @Value("${voucher.grpc.port:9086}") int port, @Value("${voucher.grpc.token:}") String token,
            @Value("${voucher.grpc.certificate:}") String certificate, @Value("${voucher.grpc.private-key:}") String key)
            throws Exception {
        var builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port));
        if (certificate.isBlank() != key.isBlank()) throw new IllegalArgumentException("Both TLS certificate and private key are required");
        if (!certificate.isBlank()) builder.useTransportSecurity(new File(certificate), new File(key));
        else if (!InetAddress.getByName(host).isLoopbackAddress())
            throw new IllegalArgumentException("Non-loopback gRPC requires TLS");
        ServerInterceptor authentication = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                String provided = headers.get(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER));
                if (token.isBlank() || provided == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
        server = builder.addService(ServerInterceptors.intercept(service, authentication)).build();
    }

    @Override public void start() {
        try { server.start(); running = true; }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot start voucher gRPC server", error); }
    }
    @Override public void stop() { server.shutdownNow(); running = false; }
    @Override public boolean isRunning() { return running; }
    int port() { return server.getPort(); }
}
