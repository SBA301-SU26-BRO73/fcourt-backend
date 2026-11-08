package com.fcourt.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically finishes interrupted full-court sagas (SCRUM-242): reservation, commit and cancel lock release. */
@Component
@ConditionalOnProperty(name = "booking.full-court.reconcile-enabled", havingValue = "true", matchIfMissing = true)
class FullCourtReconciler {
    private static final Logger log = LoggerFactory.getLogger(FullCourtReconciler.class);
    private final FullCourtReservation reservations;
    private final FullCourtManagement management;

    FullCourtReconciler(FullCourtReservation reservations, FullCourtManagement management) {
        this.reservations = reservations;
        this.management = management;
    }

    @Scheduled(fixedDelayString = "${booking.full-court.reconcile-delay-ms:30000}")
    void run() {
        try {
            int done = reservations.reconcile() + management.reconcile();
            if (done > 0) log.info("Reconciled {} full-court package(s)", done);
        } catch (RuntimeException error) {
            log.warn("Full-court reconciliation failed; it will retry", error);
        }
    }
}
