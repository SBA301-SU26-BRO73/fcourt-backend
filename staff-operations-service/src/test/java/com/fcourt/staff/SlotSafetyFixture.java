package com.fcourt.staff;

import com.fcourt.staff.grpc.BookingSafetyGrpc;
import com.fcourt.staff.grpc.CheckSlotsRequest;
import com.fcourt.staff.grpc.CheckSlotsResponse;
import com.fcourt.staff.grpc.HoldSafetyGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

final class SlotSafetyFixture implements AutoCloseable {
    enum Mode { OK, HOLD_BUSY, BOOKING_BUSY, MALFORMED, UNAVAILABLE, TIMEOUT }
    volatile Mode mode = Mode.OK;
    final AtomicInteger holdCalls = new AtomicInteger();
    final AtomicInteger bookingCalls = new AtomicInteger();
    private final Server server;

    SlotSafetyFixture() {
        try {
            var authentication = new ServerInterceptor() {
                @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                                           ServerCallHandler<Q, R> next) {
                    String auth = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
                    if (auth == null || !auth.startsWith("Bearer ")) {
                        call.close(Status.UNAUTHENTICATED, new Metadata());
                        return new ServerCall.Listener<>() { };
                    }
                    return next.startCall(call, headers);
                }
            };
            server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new HoldSafetyGrpc.HoldSafetyImplBase() {
                @Override public void check(CheckSlotsRequest request, StreamObserver<CheckSlotsResponse> reply) {
                    holdCalls.incrementAndGet(); respond(request, reply, Mode.HOLD_BUSY);
                }
            }, authentication)).addService(ServerInterceptors.intercept(new BookingSafetyGrpc.BookingSafetyImplBase() {
                @Override public void check(CheckSlotsRequest request, StreamObserver<CheckSlotsResponse> reply) {
                    bookingCalls.incrementAndGet(); respond(request, reply, Mode.BOOKING_BUSY);
                }
            }, authentication)).build().start();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    private void respond(CheckSlotsRequest request, StreamObserver<CheckSlotsResponse> reply, Mode busyMode) {
        if (mode == Mode.TIMEOUT) return;
        if (mode == Mode.UNAVAILABLE) { reply.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
        var response = CheckSlotsResponse.newBuilder().setCourtId(request.getCourtId()).setBranchId(request.getBranchId())
                .setDate(request.getDate()).addAllCheckedSlotIds(request.getSlotIdsList()).setComplete(mode != Mode.MALFORMED);
        if (mode == busyMode) response.addBusySlotIds(request.getSlotIds(0));
        reply.onNext(response.build());
        reply.onCompleted();
    }

    String target() { return "localhost:" + server.getPort(); }
    void reset() { mode = Mode.OK; holdCalls.set(0); bookingCalls.set(0); }
    @Override public void close() { server.shutdownNow(); }
}
