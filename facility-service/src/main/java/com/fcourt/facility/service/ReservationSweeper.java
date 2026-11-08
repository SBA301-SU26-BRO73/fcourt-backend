package com.fcourt.facility.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases uncommitted full-court reservations whose lease passed (SCRUM-241). Correctness does not depend on it:
 * readers already ignore an expired lease; the sweeper only makes the stored state match and fires the change event.
 */
@Component
class ReservationSweeper {
    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private final ReservationService reservations;

    ReservationSweeper(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${facility.reservations.sweep-delay-ms:15000}")
    void sweep() {
        try {
            int released = reservations.expireDue();
            if (released > 0) log.info("Released {} expired court reservation(s)", released);
        } catch (RuntimeException error) {
            log.warn("Court reservation sweep failed; it will retry", error);
        }
    }
}
