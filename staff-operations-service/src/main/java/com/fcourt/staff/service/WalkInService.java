package com.fcourt.staff.service;

import com.fcourt.staff.api.WalkInController.WalkInRequest;
import com.fcourt.staff.integration.AvailabilityGateway;
import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.integration.StaffUpstreams;
import com.fcourt.staff.integration.SlotSafetyGateway;
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
    private final SlotSafetyGateway safety;
    private final WalkInPricing pricing;

    public WalkInService(StaffService staff, StaffUpstreams facility, AvailabilityGateway availability,
                          BookingGateway booking, BookingCommandStore commands, Clock clock, ZoneId zone,
                          SlotSafetyGateway safety, WalkInPricing pricing) {
        this.staff = staff; this.facility = facility; this.availability = availability;
        this.booking = booking; this.commands = commands; this.clock = clock; this.zone = zone;
        this.safety = safety; this.pricing = pricing;
    }

    @Transactional(timeout = 45)
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
                request.date(), request.slotIds(), request.guestPhone(), "CASH", clock.instant(), null);
        String upstreamKey = "staff:walk-in:" + actor + ":" + key;
        BookingGateway.CashQuote expectedQuote = null;
        var result = booking.reconcileWalkIn(command, upstreamKey, jwt.getTokenValue());
        if (result == null) {
            if (!today.equals(request.date())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Walk-in must be for today");
            facility.courtInBranch(request.courtId(), assignment.branchId(), jwt.getTokenValue());
            var quote = pricing.quote(availability.selected(request.courtId(), request.date(), request.slotIds()));
            expectedQuote = quote;
            safety.check(assignment.branchId(), request.courtId(), request.date(), request.slotIds(), actor, upstreamKey, jwt.getTokenValue());
            command = new BookingGateway.WalkInCommand(assignment.branchId(), actor, request.courtId(), request.date(),
                    request.slotIds(), request.guestPhone(), "CASH", clock.instant(), quote);
            result = booking.walkIn(command, upstreamKey, jwt.getTokenValue());
            if (result.amount() == null || quote.totalAmount().compareTo(result.amount()) != 0) throw BookingGateway.unavailable();
        }
        if (result.bookingId() <= 0 || result.bookingCode() == null || result.bookingCode().isBlank()
                || result.bookingCode().length() > 10 || result.branchId() != assignment.branchId()
                || result.courtId() != request.courtId() || !request.date().equals(result.date())
                || !"CHECKED_IN".equals(result.status()) || !"CASH".equals(result.paymentMethod())
                || result.checkedInAt() == null || result.amount() == null || result.amount().signum() < 0
                || result.amount().scale() > 2 || result.amount().compareTo(new java.math.BigDecimal("99999999.99")) > 0) throw BookingGateway.unavailable();
        validateInvoice(result, actor, request.slotIds(), expectedQuote);
        commands.complete(actor, key, "WalkInBookingCreatedEvent", fingerprint, result, result.bookingId(),
                Map.of("booking", result, "actorId", actor, "occurredAt", result.checkedInAt()));
        return result;
    }

    private void validateInvoice(BookingGateway.WalkInResult result, long actor, List<Long> ids, BookingGateway.CashQuote quote) {
        var invoice = result.invoice();
        if (invoice == null || invoice.receiptCode() == null || invoice.receiptCode().isBlank() || invoice.receiptCode().length() > 64
                || invoice.policyVersion() == null || invoice.policyVersion().isBlank() || invoice.cashierUserId() != actor
                || invoice.baseAmount() == null || invoice.surchargeAmount() == null || invoice.totalAmount() == null
                || invoice.baseAmount().signum() < 0 || invoice.surchargeAmount().signum() < 0 || invoice.items() == null
                || invoice.items().size() != ids.size() || invoice.baseAmount().add(invoice.surchargeAmount()).compareTo(result.amount()) != 0
                || invoice.totalAmount().compareTo(result.amount()) != 0) throw BookingGateway.unavailable();
        var found = new HashSet<Long>();
        var base = java.math.BigDecimal.ZERO;
        for (var item : invoice.items()) {
            if (item == null || !ids.contains(item.slotId()) || !found.add(item.slotId()) || item.price() == null
                    || item.price().signum() < 0 || item.price().scale() > 2 || item.startTime() == null || item.endTime() == null) throw BookingGateway.unavailable();
            base = base.add(item.price());
        }
        if (base.compareTo(invoice.baseAmount()) != 0) throw BookingGateway.unavailable();
        if (quote != null) {
            if (quote.baseAmount().compareTo(invoice.baseAmount()) != 0 || quote.surchargeAmount().compareTo(invoice.surchargeAmount()) != 0
                    || !quote.policyVersion().equals(invoice.policyVersion())) throw BookingGateway.unavailable();
            for (var expected : quote.items()) {
                var actual = invoice.items().stream().filter(item -> item.slotId() == expected.slotId()).findFirst().orElseThrow();
                if (!expected.startTime().equals(actual.startTime()) || !expected.endTime().equals(actual.endTime())
                        || expected.price().compareTo(actual.price()) != 0) throw BookingGateway.unavailable();
            }
        }
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
