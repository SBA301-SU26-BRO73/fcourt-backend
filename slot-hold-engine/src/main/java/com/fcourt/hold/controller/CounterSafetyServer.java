package com.fcourt.hold.controller;

import com.fcourt.hold.client.HoldStaffIdentity;
import com.fcourt.hold.service.CounterHolds;

import com.fcourt.staff.grpc.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.net.*;
import java.time.LocalDate;
import java.util.HashSet;

@Component
@ConditionalOnProperty(name = "counter.grpc.enabled", havingValue = "true")
public class CounterSafetyServer implements SmartLifecycle {
    private static final Context.Key<String> BEARER = Context.key("counter-bearer");
    private final Server server;
    private volatile boolean running;
    private final HoldStaffIdentity identity;
    private final CounterHolds holds;

    public CounterSafetyServer(
            HoldStaffIdentity identity,
            CounterHolds holds,
            @Value("${counter.grpc.host:127.0.0.1}") String host,
            @Value("${counter.grpc.port:9087}") int port,
            @Value("${counter.grpc.certificate:}") String certificate,
            @Value("${counter.grpc.private-key:}") String key)
            throws Exception {
        this.identity = identity;
        this.holds = holds;
        var builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port));
        if (!certificate.isBlank() && !key.isBlank())
            builder.useTransportSecurity(new File(certificate), new File(key));
        else if (!InetAddress.getByName(host).isLoopbackAddress())
            throw new IllegalArgumentException(
                    "Non-loopback gRPC requires TLS certificate and key");
        var service = new HoldService();
        ServerInterceptor bearer =
                new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        String token =
                                headers.get(
                                        Metadata.Key.of(
                                                "authorization", Metadata.ASCII_STRING_MARSHALLER));
                        return Contexts.interceptCall(
                                Context.current().withValue(BEARER, token == null ? "" : token),
                                call,
                                headers,
                                next);
                    }
                };
        server = builder.addService(ServerInterceptors.intercept(service, bearer)).build();
    }

    private class HoldService extends HoldSafetyGrpc.HoldSafetyImplBase {
        @Override
        public void check(CheckSlotsRequest request, StreamObserver<CheckSlotsResponse> observer) {
            try {
                String bearer = BEARER.get();
                if (bearer == null || !bearer.startsWith("Bearer ")) {
                    observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
                    return;
                }
                LocalDate date = LocalDate.parse(request.getDate());
                if (request.getCourtId() <= 0
                        || request.getSlotIdsCount() < 1
                        || request.getSlotIdsCount() > 48
                        || request.getSlotIdsList().stream().anyMatch(id -> id <= 0)
                        || new HashSet<>(request.getSlotIdsList()).size()
                                != request.getSlotIdsCount()) throw new IllegalArgumentException();
                identity.require(bearer, request.getBranchId(), request.getActorId());
                var busy =
                        holds.busy(
                                request.getCourtId(),
                                date,
                                request.getSlotIdsList(),
                                request.getCommandKey());
                observer.onNext(
                        CheckSlotsResponse.newBuilder()
                                .setBranchId(request.getBranchId())
                                .setCourtId(request.getCourtId())
                                .setDate(request.getDate())
                                .setComplete(true)
                                .addAllCheckedSlotIds(request.getSlotIdsList())
                                .addAllBusySlotIds(busy)
                                .build());
                observer.onCompleted();
            } catch (org.springframework.security.oauth2.jwt.JwtException e) {
                observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            } catch (ResponseStatusException e) {
                var status =
                        switch (e.getStatusCode().value()) {
                            case 401 -> Status.UNAUTHENTICATED;
                            case 403, 404 -> Status.PERMISSION_DENIED;
                            case 409 -> Status.FAILED_PRECONDITION;
                            default -> Status.UNAVAILABLE;
                        };
                observer.onError(status.asRuntimeException());
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
            } catch (Exception e) {
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        }
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot start counter safety server", e);
        }
    }

    @Override
    public void stop() {
        server.shutdownNow();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int port() {
        return server.getPort();
    }
}
