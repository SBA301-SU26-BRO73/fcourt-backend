package com.fcourt.staff.integration;

import com.fcourt.staff.grpc.BookingSafetyGrpc;
import com.fcourt.staff.grpc.CheckSlotsRequest;
import com.fcourt.staff.grpc.CheckSlotsResponse;
import com.fcourt.staff.grpc.HoldSafetyGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SlotSafetyGateway {
    private final ManagedChannel holds;
    private final ManagedChannel bookings;
    private final long deadlineMs;

    public SlotSafetyGateway(@Value("${staff.grpc.hold-target}") String holdTarget,
                              @Value("${staff.grpc.booking-target}") String bookingTarget,
                              @Value("${staff.grpc.plaintext:false}") boolean plaintext,
                              @Value("${staff.grpc.deadline-ms:2000}") long deadlineMs) {
        if (deadlineMs < 1 || deadlineMs > 10000) throw new IllegalArgumentException("Invalid gRPC deadline");
        this.holds = channel(holdTarget, plaintext);
        this.bookings = channel(bookingTarget, plaintext);
        this.deadlineMs = deadlineMs;
    }

    public void check(long branch, long court, LocalDate date, List<Long> ids, long actor, String key, String token) {
        var request = CheckSlotsRequest.newBuilder().setBranchId(branch).setCourtId(court).setDate(date.toString())
                .addAllSlotIds(ids).setActorId(actor).setCommandKey(key).build();
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        var credentials = MetadataUtils.newAttachHeadersInterceptor(headers);
        try {
            validate(request, HoldSafetyGrpc.newBlockingStub(holds).withInterceptors(credentials)
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).check(request));
            validate(request, BookingSafetyGrpc.newBlockingStub(bookings).withInterceptors(credentials)
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).check(request));
        } catch (StatusRuntimeException error) {
            HttpStatus status = switch (error.getStatus().getCode()) {
                case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
                case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
                case ALREADY_EXISTS, FAILED_PRECONDITION -> HttpStatus.CONFLICT;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ResponseStatusException(status, "Slot safety dependency rejected or unavailable");
        }
    }

    private void validate(CheckSlotsRequest request, CheckSlotsResponse response) {
        var requested = new HashSet<>(request.getSlotIdsList());
        if (!response.getComplete() || response.getBranchId() != request.getBranchId()
                || response.getCourtId() != request.getCourtId() || !response.getDate().equals(request.getDate())
                || !new HashSet<>(response.getCheckedSlotIdsList()).equals(requested)
                || response.getCheckedSlotIdsCount() != requested.size()
                || !requested.containsAll(response.getBusySlotIdsList())) throw BookingGateway.unavailable();
        if (response.getBusySlotIdsCount() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected slot is held or booked online");
        }
    }

    private ManagedChannel channel(String target, boolean plaintext) {
        var builder = ManagedChannelBuilder.forTarget(target).maxInboundMessageSize(65536);
        if (plaintext) builder.usePlaintext();
        else builder.useTransportSecurity();
        return builder.build();
    }

    @PreDestroy
    public void close() { holds.shutdownNow(); bookings.shutdownNow(); }
}
