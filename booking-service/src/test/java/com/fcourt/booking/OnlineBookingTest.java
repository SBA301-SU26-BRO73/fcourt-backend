package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(
    properties = {
      "booking.events-enabled=false",
      "booking.online-release-enabled=false",
      "booking.online-expiry-enabled=false",
      "STAFF_BOOKING_SERVICE_TOKEN=fixture-staff-booking-token-not-production"
    })
@AutoConfigureMockMvc
class OnlineBookingTest {
  static final Instant NOW = Instant.parse("2030-01-01T02:00:00Z");
  static final LocalDate DAY = LocalDate.of(2030, 1, 1);

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock onlineClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
  }

  @Autowired OnlineBooking service;
  @Autowired StaffCounter counter;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;
  @Autowired Clock clock;
  @MockBean OnlineBookingDependencies upstream;
  @MockBean CounterDependencies facility;
  @MockBean StaffAuthority staffAuthority;
  final Jwt customer = Jwt.withTokenValue("fixture").header("alg", "none").subject("200").build();
  OnlineBooking.Request request;

  @BeforeEach
  void seed() {
    jdbc.execute("TRUNCATE bookings CASCADE");
    reset(upstream, facility);
    request = new OnlineBooking.Request(UUID.randomUUID(), 21, DAY, List.of(1L, 2L));
    when(upstream.customer(any()))
        .thenReturn(new OnlineBookingDependencies.Account(200, "CUSTOMER", "ACTIVE", "Guest"));
    when(upstream.context(21))
        .thenReturn(
            new OnlineBookingDependencies.Context(21, 7, "Court 1", "Branch", "Address", null));
    when(facility.canonicalSlots(21, DAY))
        .thenReturn(List.of(slot(1, "10:00", "10:30"), slot(2, "10:30", "11:00")));
    when(upstream.claim(anyLong(), any(), any()))
        .thenAnswer(
            invocation -> {
              OnlineBooking.Request r = invocation.getArgument(2);
              return new OnlineBookingDependencies.Lease(
                  OnlineBooking.hash(r.holdToken().toString()),
                  NOW.plusSeconds(300),
                  21,
                  DAY,
                  r.slotIds(),
                  "0901234567");
            });
  }

  static CounterDependencies.Slot slot(long id, String start, String end) {
    return new CounterDependencies.Slot(
        id, LocalTime.parse(start), LocalTime.parse(end), new BigDecimal("50000"), "AVAILABLE");
  }

  @Test
  void serverPricesAndSnapshotsPersistAndRetryDoesNotRebook() {
    UUID key = UUID.randomUUID();
    var result = service.create(customer, key, request);
    assertThat(result.status()).isEqualTo("PENDING_PAYMENT");
    assertThat(result.totalPrice()).isEqualByComparingTo("100000");
    assertThat(result.branchId()).isEqualTo(7);
    assertThat(result.customerName()).isEqualTo("Guest");
    assertThat(result.slots()).hasSize(2);
    assertThat(result.holdExpiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(service.create(customer, key, request).id()).isEqualTo(result.id());
    verify(upstream, times(1)).claim(anyLong(), any(), any());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT hold_token_hash FROM bookings", String.class))
        .isEqualTo(OnlineBooking.hash(request.holdToken().toString()));
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    key,
                    new OnlineBooking.Request(UUID.randomUUID(), 21, DAY, List.of(1L))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void concurrentCommandsCannotBookSameSlotsTwice() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new CountDownLatch(1);
      var results = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    service.create(customer, UUID.randomUUID(), request);
                    return 201;
                  } catch (ResponseStatusException e) {
                    return e.getStatusCode().value();
                  }
                }));
      gate.countDown();
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM booking_slots WHERE active", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void rejectedClaimLeavesNoBookingOrCommand() {
    doThrow(new ResponseStatusException(HttpStatus.CONFLICT))
        .when(upstream)
        .claim(anyLong(), any(), any());
    assertThatThrownBy(() -> service.create(customer, UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM online_booking_commands", Integer.class))
        .isZero();
  }

  @Test
  void changedTemplateStartCannotOverlapAnExistingReservation() {
    jdbc.update(
        "INSERT INTO bookings(court_id,customer_id,date,total_price,booking_code) VALUES"
            + " (21,200,?,50000,'OLD')",
        DAY);
    jdbc.update(
        "INSERT INTO"
            + " booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking)"
            + " SELECT id,21,?,'10:15','10:45',50000 FROM bookings",
        DAY);
    assertThatThrownBy(() -> service.create(customer, UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    verify(upstream, never()).claim(anyLong(), any(), any());
    var intervals = SlotOccupancy.active(jdbc, 21, DAY);
    assertThat(intervals.getFirst().overlaps(LocalTime.of(10, 45), LocalTime.of(11, 15))).isFalse();
    assertThat(intervals.getFirst().overlaps(LocalTime.of(10, 30), LocalTime.of(11, 0))).isTrue();
  }

  @Test
  void onlineAndWalkInShareOneDurableOccupancyDecision() throws Exception {
    var slots = List.of(slot(1, "10:00", "10:30"), slot(2, "10:30", "11:00"));
    when(facility.courts(eq(7L), any()))
        .thenReturn(List.of(new CounterDependencies.Court(21, 7, "Court 1", "ACTIVE")));
    when(facility.slots(21, DAY)).thenReturn(slots);
    var price = new BigDecimal("100000");
    var quote =
        new StaffCounter.Quote(
            price,
            BigDecimal.ZERO,
            price,
            "counter-v1",
            slots.stream()
                .map(s -> new StaffCounter.Item(s.slotId(), s.startTime(), s.endTime(), s.price()))
                .toList());
    var walkIn =
        new StaffCounter.WalkIn(
            7, 100, 21, DAY, List.of(1L, 2L), "0901234567", "CASH", NOW, quote, "Guest");
    var staff = Jwt.withTokenValue("fixture-staff").header("alg", "none").subject("100").build();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new CountDownLatch(1);
      var online =
          pool.submit(
              () -> {
                gate.await();
                try {
                  service.create(customer, UUID.randomUUID(), request);
                  return 201;
                } catch (ResponseStatusException e) {
                  return e.getStatusCode().value();
                }
              });
      var walk =
          pool.submit(
              () -> {
                gate.await();
                try {
                  counter.create(
                      staff,
                      "staff:walk-in:100:" + UUID.randomUUID(),
                      "fixture-staff-booking-token-not-production",
                      walkIn);
                  return 201;
                } catch (ResponseStatusException e) {
                  return e.getStatusCode().value();
                }
              });
      gate.countDown();
      assertThat(List.of(online.get(), walk.get())).containsExactlyInAnyOrder(201, 409);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM booking_slots WHERE active", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void invalidOrUnavailableSelectionsNeverReachClaim() {
    for (var invalid :
        List.of(
            new OnlineBooking.Request(request.holdToken(), 21, DAY, List.of(1L, 1L)),
            new OnlineBooking.Request(request.holdToken(), 21, DAY, List.of(99L))))
      assertThatThrownBy(() -> service.create(customer, UUID.randomUUID(), invalid))
          .isInstanceOf(ResponseStatusException.class);
    when(facility.canonicalSlots(21, DAY))
        .thenReturn(List.of(slot(1, "08:00", "08:30"), slot(2, "10:30", "11:00")));
    assertThatThrownBy(() -> service.create(customer, UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    verify(upstream, never()).claim(anyLong(), any(), any());
  }

  @Test
  void httpRejectsMissingIdentityAndClientPrices() throws Exception {
    String body = json.writeValueAsString(request);
    mvc.perform(post("/api/bookings").contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/bookings")
                .with(jwt().jwt(j -> j.subject("200")))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    var altered = json.readTree(body);
    ((com.fasterxml.jackson.databind.node.ObjectNode) altered).put("totalPrice", 1);
    mvc.perform(
            post("/api/bookings")
                .with(jwt().jwt(j -> j.subject("200")))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(altered.toString()))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
  }

  @Test
  void expiryWaitsForPaymentDecisionAndPublishesOneCancellation() {
    long id = service.create(customer, UUID.randomUUID(), request).id();
    jdbc.update(
        "UPDATE bookings SET hold_expires_at=? WHERE id=?",
        java.sql.Timestamp.from(NOW.minusSeconds(1)),
        id);
    var expiry = new OnlineBookingExpiry(jdbc, upstream, tx, json, clock);
    when(upstream.mayExpire(eq(id), any())).thenReturn(false);
    expiry.expire(id);
    assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, id))
        .isEqualTo("PENDING_PAYMENT");
    when(upstream.mayExpire(eq(id), any())).thenReturn(true);
    expiry.expire(id);
    expiry.expire(id);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM booking_slots WHERE active", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class))
        .isEqualTo(1);
    try {
      var event =
          json.readTree(
              jdbc.queryForObject("SELECT payload::text FROM booking_outbox", String.class));
      assertThat(event.path("booking").path("slots").get(0).path("date").asText())
          .isEqualTo(DAY.toString());
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void durableReleaseRetriesWithoutChangingBooking() {
    service.create(customer, UUID.randomUUID(), request);
    var worker = new OnlineHoldReleaseWorker(jdbc, upstream, tx);
    doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
        .when(upstream)
        .release(anyString());
    worker.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM online_hold_releases WHERE released_at IS NULL",
                Integer.class))
        .isEqualTo(1);
    doNothing().when(upstream).release(anyString());
    worker.run();
    worker.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM online_hold_releases WHERE released_at IS NOT NULL",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM booking_slots WHERE active", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void unavailableAuthoritiesStopRecoveryWithoutAcknowledgingRows() {
    service.create(customer, UUID.randomUUID(), request);
    int eventsBeforeRecovery =
        jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class);
    long second =
        jdbc.queryForObject(
            "INSERT INTO bookings(court_id,customer_id,date,total_price,booking_code) VALUES"
                + " (22,200,?,100000,'QUEUE2') RETURNING id",
            Long.class,
            DAY);
    jdbc.update(
        "INSERT INTO online_hold_releases(booking_id,hold_hash) VALUES (?,?)",
        second,
        OnlineBooking.hash(UUID.randomUUID().toString()));
    doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
        .when(upstream)
        .release(anyString());
    new OnlineHoldReleaseWorker(jdbc, upstream, tx).run();
    verify(upstream, times(1)).release(anyString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM online_hold_releases WHERE released_at IS NULL",
                Integer.class))
        .isEqualTo(2);
    jdbc.update(
        "UPDATE bookings SET hold_expires_at=?", java.sql.Timestamp.from(NOW.minusSeconds(1)));
    doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
        .when(upstream)
        .mayExpire(anyLong(), any());
    new OnlineBookingExpiry(jdbc, upstream, tx, json, clock).run();
    verify(upstream, times(1)).mayExpire(anyLong(), any());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE status='PENDING_PAYMENT'", Integer.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class))
        .isEqualTo(eventsBeforeRecovery);
  }
}
