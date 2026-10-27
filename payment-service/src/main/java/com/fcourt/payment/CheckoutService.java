package com.fcourt.payment;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class CheckoutService {
    private final PaymentUpstreams upstreams;
    private final Clock clock;

    CheckoutService(PaymentUpstreams upstreams, Clock clock) {
        this.upstreams = upstreams;
        this.clock = clock;
    }

    CheckoutInfo checkout(long bookingId, String authorization, String holdToken) {
        if ((authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7)
                && (holdToken == null || holdToken.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication or hold token required");
        }
        var booking = upstreams.booking(bookingId, authorization, holdToken);
        if (booking.id() == null || booking.id() != bookingId || booking.branchId() == null || booking.branchId() <= 0
                || booking.bookingCode() == null || !booking.bookingCode().matches("[A-Za-z0-9]{1,10}")
                || booking.holdExpiresAt() == null || booking.status() == null) {
            throw PaymentUpstreams.unavailable();
        }
        if (!booking.holdExpiresAt().isAfter(clock.instant()) || "CANCELLED".equals(booking.status())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Hold expired or booking cancelled");
        }
        if (!"PENDING_PAYMENT".equals(booking.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is not awaiting payment");
        }
        if (booking.totalPrice() == null || booking.totalPrice().signum() <= 0
                || booking.date() == null || blank(booking.branchName()) || blank(booking.courtName())
                || booking.slots() == null || booking.slots().isEmpty()
                || booking.slots().stream().anyMatch(slot -> slot == null || slot.startTime() == null
                    || slot.endTime() == null || slot.price() == null || slot.price().signum() < 0)) {
            throw PaymentUpstreams.unavailable();
        }
        java.math.BigDecimal amount;
        try {
            // Vietnamese bank transfers use whole VND. Never round an invalid amount.
            amount = booking.totalPrice().setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw PaymentUpstreams.unavailable();
        }
        var bank = upstreams.bank(booking.branchId());
        if (bank.id() == null || !bank.id().equals(booking.branchId()) || blank(bank.bankName())
                || blank(bank.bankAccountNumber()) || blank(bank.bankAccountName())
                || !bank.bankAccountNumber().matches("[0-9]{1,50}")
                || bank.bankBin() == null || !bank.bankBin().matches("[0-9]{6}")) {
            throw PaymentUpstreams.unavailable();
        }
        // The upstream calls must not allow a checkout that expired in flight.
        if (!booking.holdExpiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Hold expired");
        }
        return new CheckoutInfo(booking.id(), booking.bookingCode(), booking.branchId(),
                booking.branchName(), booking.courtName(), booking.date(), List.copyOf(booking.slots()),
                amount, bank.bankName(), bank.bankBin(), bank.bankAccountNumber(), bank.bankAccountName(),
                "FC " + booking.bookingCode(), booking.holdExpiresAt(), booking.customerName());
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
