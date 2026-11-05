package com.fcourt.voucher;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class VoucherReservationExpiry {
    private final VoucherRedemptions applications;
    private final VoucherBookingClient booking;
    private final TransactionTemplate tx;
    private final Clock clock;
    VoucherReservationExpiry(VoucherRedemptions applications, VoucherBookingClient booking, TransactionTemplate tx, Clock clock) {
        this.applications = applications; this.booking = booking; this.tx = tx; this.clock = clock;
    }

    @Scheduled(fixedDelayString="${voucher.reservation-expiry-ms:60000}")
    void expire() {
        for (var application : applications.expired(clock.instant())) {
            try { reconcile(application); }
            catch (RuntimeException error) {
                LoggerFactory.getLogger(getClass()).warn("Voucher reservation remains pending for booking {}", application.bookingId());
            }
        }
    }

    void reconcile(VoucherRedemption application) {
        var order = booking.internal(application.bookingId());
        if (order.customerId() == null || order.customerId() != application.customerId() || order.branchId() != application.branchId())
            throw new IllegalStateException("Booking reservation identity mismatch");
        boolean releasable = !order.paymentFrozen() && ("CANCELLED".equals(order.status())
                || (order.holdExpiresAt() != null && !order.holdExpiresAt().isAfter(clock.instant())));
        if (!releasable) return;
        // A frozen checkout may still be approved after hold expiry. Never free it by TTL alone.
        tx.executeWithoutResult(ignored -> {
            applications.lockVoucher(application.voucherId());
            applications.release(application.bookingId());
        });
    }
}
