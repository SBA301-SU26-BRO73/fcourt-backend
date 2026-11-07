package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class OnlineBookingDependenciesTest {
  static final String SECRET = "fixture-online-service-secret-not-production";
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  HttpServer server;
  Map<String, Object> account;
  boolean fail, wrongLease, redirect;
  final java.util.concurrent.atomic.AtomicInteger redirected =
      new java.util.concurrent.atomic.AtomicInteger();
  OnlineBooking.Request request;
  OnlineBookingDependencies client;
  final Jwt jwt =
      Jwt.withTokenValue("fixture-customer").header("alg", "none").subject("200").build();

  @BeforeEach
  void start() throws Exception {
    account =
        new HashMap<>(
            Map.of(
                "id",
                200,
                "role",
                "CUSTOMER",
                "status",
                "ACTIVE",
                "fullName",
                "Customer",
                "extra",
                "ignored"));
    request =
        new OnlineBooking.Request(UUID.randomUUID(), 21, LocalDate.now().plusDays(1), List.of(1L));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          Object value;
          int code = 200;
          if (path.equals("/capture")) {
            redirected.incrementAndGet();
            value = account;
          } else if (redirect) {
            code = 302;
            exchange.getResponseHeaders().set("Location", "/capture");
            value = Map.of();
          } else if (fail) {
            code = 503;
            value = Map.of("private", "sensitive-provider-details");
          } else if (path.equals("/api/auth/me")) {
            if (!"Bearer fixture-customer"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) code = 401;
            value = account;
          } else if (path.endsWith("/booking-context"))
            value =
                Map.of(
                    "courtId",
                    21,
                    "branchId",
                    7,
                    "courtName",
                    "Court",
                    "branchName",
                    "Venue",
                    "branchAddress",
                    "Address");
          else {
            if (!SECRET.equals(exchange.getRequestHeaders().getFirst("X-Booking-Service-Token")))
              code = 403;
            if (path.endsWith("/claim"))
              value =
                  Map.of(
                      "holdHash",
                      wrongLease ? "wrong" : OnlineBooking.hash(request.holdToken().toString()),
                      "expiresAt",
                      Instant.now().plusSeconds(120),
                      "courtId",
                      21,
                      "date",
                      request.date(),
                      "slotIds",
                      List.of(1),
                      "guestPhone",
                      "0901234567");
            else if (path.endsWith("/booking-expiry"))
              value = Map.of("bookingId", 42, "mayExpire", true);
            else {
              code = 204;
              value = Map.of();
            }
          }
          byte[] bytes = json.writeValueAsBytes(value);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
          if (code != 204) exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    client =
        new OnlineBookingDependencies(RestClient.builder(), url, url, url, SECRET, url, SECRET);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void configuredHttpContractsValidateIdentityAndCarryScopedCredentials() {
    assertThat(client.customer(jwt).id()).isEqualTo(200);
    assertThat(client.context(21).branchId()).isEqualTo(7);
    assertThat(client.claim(200, UUID.randomUUID(), request).slotIds()).containsExactly(1L);
    client.release(OnlineBooking.hash(request.holdToken().toString()));
    assertThat(client.mayExpire(42, Instant.now().minusSeconds(1))).isTrue();
  }

  @Test
  void accountChangesAndMismatchedIdentityFailClosed() {
    account.put("role", "ADMIN");
    assertThatThrownBy(() -> client.customer(jwt)).isInstanceOf(ResponseStatusException.class);
    account.put("role", "CUSTOMER");
    account.put("status", "INACTIVE");
    assertThatThrownBy(() -> client.customer(jwt)).isInstanceOf(ResponseStatusException.class);
    account.put("status", "ACTIVE");
    account.put("id", 201);
    assertThatThrownBy(() -> client.customer(jwt)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void invalidLeaseAndUnavailableUpstreamsCannotAuthorizeCreation() {
    wrongLease = true;
    assertThatThrownBy(() -> client.claim(200, UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    fail = true;
    assertThatThrownBy(() -> client.customer(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("sensitive-provider-details");
  }

  @Test
  void authenticationRedirectIsNotFollowed() {
    redirect = true;
    assertThatThrownBy(() -> client.customer(jwt)).isInstanceOf(ResponseStatusException.class);
    assertThat(redirected).hasValue(0);
  }
}
