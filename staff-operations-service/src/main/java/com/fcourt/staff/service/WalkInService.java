package com.fcourt.staff.service;

import com.fcourt.staff.api.WalkInController.WalkInRequest;
import com.fcourt.staff.integration.AvailabilityGateway;
import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.integration.StaffUpstreams;
import com.fcourt.staff.persistence.BookingCommandStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalkInService {
    private final StaffService staff;
    private final StaffUpstreams facility;
    private final AvailabilityGateway availability;
    private final BookingGateway booking;
    private final BookingCommandStore commands;
    private final Clock clock;
    private final ZoneId zone;

    public WalkInService(StaffService staff, StaffUpstreams facility, AvailabilityGateway availability,
                          BookingGateway booking, BookingCommandStore commands, Clock clock, ZoneId zone) {
        this.staff = staff; this.facility = facility; this.availability = availability;
        this.booking = booking; this.commands = commands; this.clock = clock; this.zone = zone;
    }

    @Transactional(timeout = 25)
    public BookingGateway.WalkInResult create(Jwt jwt, UUID key, WalkInRequest request) {
        var assignment = staff.assigned(jwt);
        long actor = Long.parseLong(jwt.getSubject());
        String fingerprint = commands.fingerprint(List.of(assignment.branchId(), request));
        var replay = commands.replay(actor, key, "WalkInBookingCreatedEvent", fingerprint, BookingGateway.WalkInResult.class);
        if (replay != null) return replay;
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (new HashSet<>(request.slotIds()).size() != request.slotIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate slot IDs");
        }
        var command = new BookingGateway.WalkInCommand(assignment.branchId(), actor, request.courtId(),
                request.date(), request.slotIds(), request.guestPhone(), "CASH", clock.instant());
        String upstreamKey = "staff:walk-in:" + actor + ":" + key;
        var result = booking.reconcileWalkIn(command, upstreamKey, jwt.getTokenValue());
        if (result == null) {
            if (!today.equals(request.date())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Walk-in must be for today");
            facility.courtInBranch(request.courtId(), assignment.branchId(), jwt.getTokenValue());
            availability.selected(request.courtId(), request.date(), request.slotIds());
            result = booking.walkIn(command, upstreamKey, jwt.getTokenValue());
        }
        if (result.bookingId() <= 0 || result.bookingCode() == null || result.branchId() != assignment.branchId()
                || result.courtId() != request.courtId() || !request.date().equals(result.date())
                || !"CHECKED_IN".equals(result.status()) || !"CASH".equals(result.paymentMethod())
                || result.checkedInAt() == null || result.amount() == null || result.amount().signum() < 0
                || result.amount().scale() > 2) throw BookingGateway.unavailable();
        commands.complete(actor, key, "WalkInBookingCreatedEvent", fingerprint, result, result.bookingId(),
                Map.of("booking", result, "actorId", actor, "occurredAt", result.checkedInAt()));
        return result;
    }

    @Transactional(timeout = 20)
    public BookingGateway.CheckoutResult checkout(Jwt jwt, long bookingId, UUID key) {
        var assignment = staff.assigned(jwt);
        long actor = Long.parseLong(jwt.getSubject());
        String fingerprint = commands.fingerprint(List.of(assignment.branchId(), bookingId));
        var replay = commands.replay(actor, key, "BookingCompletedEvent", fingerprint, BookingGateway.CheckoutResult.class);
        if (replay != null) return replay;
        var result = booking.checkout(bookingId, assignment.branchId(), actor, clock.instant(),
                "staff:checkout:" + actor + ":" + key, jwt.getTokenValue());
        if (result.bookingId() != bookingId || result.branchId() != assignment.branchId()
                || !"COMPLETED".equals(result.status()) || result.completedAt() == null) throw BookingGateway.unavailable();
        commands.complete(actor, key, "BookingCompletedEvent", fingerprint, result, bookingId,
                Map.of("booking", result, "actorId", actor, "occurredAt", result.completedAt()));
        return result;
    }
}
