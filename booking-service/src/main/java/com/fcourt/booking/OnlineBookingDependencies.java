package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class OnlineBookingDependencies {
  private final RestClient auth, facility, holds, payment;
  private final String secret;
  private final String paymentSecret;

  OnlineBookingDependencies(
      RestClient.Builder builder,
      @Value("${AUTH_URL:http://localhost:8081}") String auth,
      @Value("${FACILITY_URL:http://localhost:8083}") String facility,
      @Value("${HOLD_URL:http://localhost:8087}") String holds,
      @Value("${BOOKING_HOLD_SERVICE_TOKEN:}") String secret,
      @Value("${PAYMENT_URL:http://localhost:8089}") String payment,
      @Value("${PAYMENT_BOOKING_SERVICE_TOKEN:}") String paymentSecret) {
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(java.net.HttpURLConnection connection, String method)
              throws java.io.IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    var client = builder.clone().requestFactory(factory);
    this.auth = client.clone().baseUrl(auth).build();
    this.facility = client.clone().baseUrl(facility).build();
    this.holds = client.clone().baseUrl(holds).build();
    this.secret = secret;
    this.payment = client.clone().baseUrl(payment).build();
    this.paymentSecret = paymentSecret;
  }

  Account customer(Jwt jwt) {
    requireSecret();
    if (paymentSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
      throw unavailable();
    Account account =
        call(
            () ->
                auth.get()
                    .uri("/api/auth/me")
                    .header("Authorization", "Bearer " + jwt.getTokenValue())
                    .retrieve()
                    .body(Account.class));
    if (account == null || account.id() != BookingQueries.actor(jwt)) throw unavailable();
    if (!"CUSTOMER".equals(account.role()) || !"ACTIVE".equals(account.status()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active CUSTOMER account required");
    if (account.fullName() == null || account.fullName().isBlank()) throw unavailable();
    return account;
  }

  Context context(long court) {
    Context result =
        call(
            () ->
                facility
                    .get()
                    .uri("/api/internal/courts/{id}/booking-context", court)
                    .retrieve()
                    .body(Context.class));
    if (result == null
        || result.courtId() != court
        || result.branchId() <= 0
        || result.courtName() == null
        || result.branchName() == null
        || result.branchAddress() == null) throw unavailable();
    return result;
  }

  Lease claim(long actor, UUID key, OnlineBooking.Request request) {
    requireSecret();
    Lease lease =
        call(
            () ->
                holds
                    .post()
                    .uri("/api/internal/online-holds/claim")
                    .header("X-Booking-Service-Token", secret)
                    .body(
                        new Claim(
                            request.holdToken(),
                            actor,
                            key,
                            request.courtId(),
                            request.date(),
                            request.slotIds()))
                    .retrieve()
                    .body(Lease.class));
    if (lease == null
        || !OnlineBooking.hash(request.holdToken().toString()).equals(lease.holdHash())
        || lease.courtId() != request.courtId()
        || !request.date().equals(lease.date())
        || lease.expiresAt() == null
        || !lease.expiresAt().isAfter(Instant.now().plusSeconds(20))
        || lease.slotIds() == null
        || lease.slotIds().size() != request.slotIds().size()
        || !new HashSet<>(lease.slotIds()).equals(new HashSet<>(request.slotIds()))
        || lease.guestPhone() == null
        || !lease.guestPhone().matches("0[35789][0-9]{8}")) throw unavailable();
    return lease;
  }

  void release(String hash) {
    requireSecret();
    call(
        () ->
            holds
                .post()
                .uri("/api/internal/online-holds/release")
                .header("X-Booking-Service-Token", secret)
                .body(Map.of("holdHash", hash))
                .retrieve()
                .toBodilessEntity());
  }

  boolean mayExpire(long booking, Instant expiresAt) {
    if (paymentSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
      throw unavailable();
    var result =
        call(
            () ->
                payment
                    .post()
                    .uri("/api/internal/payments/booking-expiry")
                    .header("X-Booking-Service-Token", paymentSecret)
                    .body(Map.of("bookingId", booking, "expiresAt", expiresAt))
                    .retrieve()
                    .body(Expiry.class));
    if (result == null || result.bookingId() != booking) throw unavailable();
    return result.mayExpire();
  }

  private void requireSecret() {
    if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) throw unavailable();
  }

  private <T> T call(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (HttpClientErrorException e) {
      if (Set.of(400, 401, 403, 404, 409).contains(e.getStatusCode().value()))
        throw new ResponseStatusException(e.getStatusCode(), "Online booking dependency rejected");
      throw unavailable();
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Online booking dependency unavailable");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Account(long id, String role, String status, String fullName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Context(
      long courtId,
      long branchId,
      String courtName,
      String branchName,
      String branchAddress,
      String branchPhone) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Lease(
      String holdHash,
      Instant expiresAt,
      long courtId,
      LocalDate date,
      List<Long> slotIds,
      String guestPhone) {}

  record Claim(
      UUID holdToken,
      long actorId,
      UUID commandKey,
      long courtId,
      LocalDate date,
      List<Long> slotIds) {}

  record Expiry(long bookingId, boolean mayExpire) {}
}
