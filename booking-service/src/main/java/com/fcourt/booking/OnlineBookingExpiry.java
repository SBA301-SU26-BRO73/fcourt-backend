package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "booking.online-expiry-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OnlineBookingExpiry {
  private final JdbcTemplate jdbc;
  private final OnlineBookingDependencies payment;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Clock clock;

  OnlineBookingExpiry(
      JdbcTemplate jdbc,
      OnlineBookingDependencies payment,
      TransactionTemplate tx,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = jdbc;
    this.payment = payment;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${booking.online-expiry-delay-ms:5000}")
  void run() {
    var ids =
        jdbc.queryForList(
            "SELECT b.id FROM bookings b JOIN online_hold_releases r ON r.booking_id=b.id WHERE"
                + " b.status='PENDING_PAYMENT' AND b.hold_expires_at<=? ORDER BY"
                + " b.hold_expires_at,b.id LIMIT 10",
            Long.class,
            java.sql.Timestamp.from(clock.instant()));
    for (long id : ids) {
      try {
        expire(id);
      } catch (org.springframework.web.server.ResponseStatusException e) {
        /* dependency unavailable: retain occupancy for retry */
        if (e.getStatusCode().is5xxServerError()
            || e.getStatusCode().value() == 401
            || e.getStatusCode().value() == 403) break;
      }
    }
  }

  void expire(long id) {
    tx.executeWithoutResult(
        ignored -> {
          var rows =
              jdbc.queryForList(
                  "SELECT * FROM bookings WHERE id=? AND status='PENDING_PAYMENT' AND"
                      + " hold_expires_at<=? FOR UPDATE",
                  id,
                  java.sql.Timestamp.from(clock.instant()));
          if (rows.isEmpty()) return;
          var booking = rows.getFirst();
          var expires = ((java.sql.Timestamp) booking.get("hold_expires_at")).toInstant();
          if (!payment.mayExpire(id, expires)) return;
          var slots =
              jdbc.queryForList(
                  "SELECT court_id AS \"courtId\",booking_date AS date,slot_start AS"
                      + " \"startTime\",slot_end AS \"endTime\" FROM booking_slots WHERE"
                      + " booking_id=?",
                  id);
          jdbc.update(
              "UPDATE bookings SET"
                  + " status='CANCELLED',payment_status='CANCELLED',cancelled_at=?,cancellation_reason='Payment"
                  + " hold expired',updated_at=now() WHERE id=?",
              java.sql.Timestamp.from(clock.instant()),
              id);
          jdbc.update("UPDATE booking_slots SET active=false WHERE booking_id=?", id);
          UUID eventId = UUID.randomUUID();
          var data = new LinkedHashMap<String, Object>();
          data.put("id", id);
          data.put("bookingId", id);
          data.put("branchId", booking.get("branch_id"));
          data.put("customerId", booking.get("customer_id"));
          data.put("bookingCode", booking.get("booking_code"));
          data.put("status", "CANCELLED");
          data.put("reason", "Payment hold expired");
          data.put("slots", slots);
          data.put("paymentId", booking.get("payment_id"));
          data.put("paymentStatus", "CANCELLED");
          data.put("refundAmount", 0);
          data.put("cancelledByRole", "SYSTEM");
          try {
            jdbc.update(
                "INSERT INTO booking_outbox(event_id,booking_id,payload) VALUES (?,?,?::jsonb)",
                eventId,
                id,
                json.writeValueAsString(
                    Map.of("eventId", eventId, "type", "BookingCancelledEvent", "booking", data)));
          } catch (Exception e) {
            throw new IllegalStateException("Cannot persist booking expiry event", e);
          }
        });
  }
}
