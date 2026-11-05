package com.fcourt.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Serializes the expiry decision with receipt submission, including absent rows. */
@RestController
class BookingExpiryController {
  private final PaymentStore store;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final byte[] credential;

  BookingExpiryController(
      PaymentStore store,
      TransactionTemplate tx,
      Clock clock,
      @Value("${PAYMENT_BOOKING_SERVICE_TOKEN:}") String credential) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
    this.credential = credential.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping("/api/internal/payments/booking-expiry")
  ResponseEntity<Decision> decide(
      @RequestHeader(value = "X-Booking-Service-Token", required = false) String secret,
      @Valid @RequestBody Request request) {
    if (credential.length < 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    if (secret == null
        || !MessageDigest.isEqual(credential, secret.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (request.expiresAt().isAfter(clock.instant()))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            tx.execute(
                ignored -> {
                  store
                      .jdbc()
                      .queryForObject(
                          "SELECT pg_advisory_xact_lock(?)", Object.class, request.bookingId());
                  var payment = store.find(request.bookingId());
                  boolean accepted =
                      payment != null
                          && (payment.submittedAt() != null
                              || "CONFIRMED".equals(payment.status()));
                  return new Decision(request.bookingId(), !accepted);
                }));
  }

  record Request(@Positive long bookingId, @NotNull Instant expiresAt) {}

  record Decision(long bookingId, boolean mayExpire) {}
}
