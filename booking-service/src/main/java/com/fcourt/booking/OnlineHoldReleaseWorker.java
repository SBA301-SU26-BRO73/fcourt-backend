package com.fcourt.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Release only after the booking is durable; ambiguous HTTP failures are retried. */
@Component
@ConditionalOnProperty(
    name = "booking.online-release-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OnlineHoldReleaseWorker {
  private final JdbcTemplate jdbc;
  private final OnlineBookingDependencies upstream;
  private final TransactionTemplate tx;

  OnlineHoldReleaseWorker(
      JdbcTemplate jdbc, OnlineBookingDependencies upstream, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.upstream = upstream;
    this.tx = tx;
  }

  @Scheduled(fixedDelayString = "${booking.online-release-delay-ms:1000}")
  void run() {
    tx.executeWithoutResult(
        status -> {
          var rows =
              jdbc.queryForList(
                  "SELECT r.booking_id,r.hold_hash FROM online_hold_releases r WHERE released_at IS"
                      + " NULL ORDER BY booking_id LIMIT 10 FOR UPDATE SKIP LOCKED");
          for (var row : rows) {
            try {
              upstream.release(row.get("hold_hash").toString());
              jdbc.update(
                  "UPDATE online_hold_releases SET released_at=now() WHERE booking_id=?",
                  row.get("booking_id"));
            } catch (org.springframework.web.server.ResponseStatusException e) {
              // Durable occupancy now rejects competing bookings even after Redis release.
              if (e.getStatusCode().is5xxServerError()
                  || e.getStatusCode().value() == 401
                  || e.getStatusCode().value() == 403) break;
            }
          }
        });
  }
}
