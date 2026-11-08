package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * SCRUM-242 saga against PostgreSQL 16 (Testcontainers, needs Docker). Facility's reservation authority and the #105
 * pricing are mocked; the booking database, its advisory locks and its unique slot index are real.
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "booking.events-enabled=false",
      "booking.online-release-enabled=false",
      "booking.online-expiry-enabled=false",
      "booking.full-court.reconcile-enabled=false",
      "STAFF_BOOKING_SERVICE_TOKEN=fixture-staff-booking-token-not-production"
    })
@AutoConfigureMockMvc
class FullCourtReservationTest {
  static final LocalDate DAY = LocalDate.of(2030, 1, 2);

  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
  }

  @Autowired FullCourtReservation service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockBean FullCourtQuote quotes;
  @MockBean FullCourtFacility facility;

  final Jwt admin =
      Jwt.withTokenValue("fixture")
          .header("alg", "none")
          .subject("10")
          .claim("roles", List.of("ADMIN"))
          .build();
  static final AtomicInteger COURTS = new AtomicInteger(1000);
  long courtA;
  long courtB;
  FullCourtReservation.Request request;

  @BeforeEach
  void seed() {
    jdbc.execute("TRUNCATE full_court_packages, bookings CASCADE");
    reset(quotes, facility);
    courtA = COURTS.addAndGet(2);
    courtB = courtA + 1;
    request = request(courtA, courtB);
    when(quotes.price(any(), any())).thenAnswer(call -> priced(call.getArgument(0)));
    when(facility.reserve(any()))
        .thenAnswer(call -> reservation(((FullCourtFacility.Reserve) call.getArgument(0)).reservationId(), "RESERVED"));
    when(facility.commit(any())).thenAnswer(call -> reservation(call.getArgument(0), "COMMITTED"));
    when(facility.rollback(any())).thenAnswer(call -> reservation(call.getArgument(0), "CANCELLED"));
  }

  static FullCourtReservation.Request request(long... courts) {
    return new FullCourtReservation.Request(
        7,
        Arrays.stream(courts).boxed().toList(),
        DAY,
        LocalTime.of(8, 0),
        LocalTime.of(9, 0),
        "Giai dau noi bo",
        "0901234567");
  }

  static FullCourtFacility.Reservation reservation(UUID id, String state) {
    return new FullCourtFacility.Reservation(id, state, List.of());
  }

  /** Two 30-minute slots per court at 10000.01 each; 12.5% off from 2 court-hours. */
  FullCourtQuote.Priced priced(FullCourtQuote.Request r) {
    var slots = new LinkedHashMap<Long, List<CounterDependencies.Slot>>();
    var names = new HashMap<Long, String>();
    for (long court : r.courtIds()) {
      slots.put(
          court,
          List.of(
              new CounterDependencies.Slot(
                  court * 10 + 1, LocalTime.of(8, 0), LocalTime.of(8, 30), new BigDecimal("10000.01"), "AVAILABLE"),
              new CounterDependencies.Slot(
                  court * 10 + 2, LocalTime.of(8, 30), LocalTime.of(9, 0), new BigDecimal("10000.01"), "AVAILABLE")));
      names.put(court, "Court " + court);
    }
    BigDecimal subtotal = new BigDecimal("20000.02").multiply(BigDecimal.valueOf(r.courtIds().size()));
    BigDecimal discount = subtotal.multiply(new BigDecimal("12.5")).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
    var quote =
        new FullCourtQuote.Quote(
            r.branchId(),
            r.courtIds(),
            r.date(),
            r.startTime(),
            r.endTime(),
            BigDecimal.valueOf(r.courtIds().size()),
            subtotal,
            new BigDecimal("12.5"),
            discount,
            subtotal.subtract(discount),
            "VND",
            false,
            "ATOMIC_AUTHORITY_REQUIRED");
    return new FullCourtQuote.Priced(quote, new FullCourtCatalog.Venue("Venue 7", names), slots);
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  void seedExistingBooking(long court, String start, String end) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO bookings(court_id,guest_phone,date,status,total_price,booking_code) VALUES (?,?,?,'CONFIRMED',1,?) RETURNING id",
            Long.class,
            court,
            "0900000000",
            DAY,
            "WK" + court);
    jdbc.update(
        "INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (?,?,?,?,?,1)",
        id,
        court,
        DAY,
        LocalTime.parse(start),
        LocalTime.parse(end));
  }

  @Test
  void createsOneBookingPerCourtWithServerPricedTotalsAndCommitsTheLock() {
    var result = service.reserve(admin, UUID.randomUUID(), request);

    assertThat(result.status()).isEqualTo(201);
    var pack = result.body();
    assertThat(pack.state()).isEqualTo("COMMITTED");
    assertThat(pack.lockState()).isEqualTo("COMMITTED");
    assertThat(pack.subtotal()).isEqualByComparingTo("40000.04");
    assertThat(pack.discountAmount()).isEqualByComparingTo("5000.00");
    assertThat(pack.total()).isEqualByComparingTo("35000.04");
    assertThat(pack.bookings()).hasSize(2);
    // The package discount is split across member bookings without losing a cent.
    assertThat(pack.bookings().stream().map(FullCourtReservation.BookingRef::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("35000.04");
    assertThat(count("SELECT count(*) FROM bookings WHERE status='CONFIRMED' AND guest_phone='0901234567' AND branch_id=7")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM booking_slots WHERE active AND booking_date=? AND court_id IN (?,?)", DAY, courtA, courtB)).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM bookings WHERE customer_name='Giai dau noi bo' AND court_name LIKE 'Court %'")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM full_court_package_bookings")).isEqualTo(2);
    var reserve = org.mockito.ArgumentCaptor.forClass(FullCourtFacility.Reserve.class);
    verify(facility).reserve(reserve.capture());
    assertThat(reserve.getValue().courtIds()).containsExactly(courtA, courtB);
    assertThat(reserve.getValue().adminId()).isEqualTo(10);
    assertThat(reserve.getValue().startAt()).isEqualTo("2030-01-02T08:00");
    assertThat(reserve.getValue().endAt()).isEqualTo("2030-01-02T09:00");
    verify(facility).commit(reserve.getValue().reservationId());
    verify(facility, never()).rollback(any());
  }

  @Test
  void replayOfTheSameKeyReturnsTheSamePackageWithoutReservingOrRepricing() {
    UUID key = UUID.randomUUID();
    var first = service.reserve(admin, key, request).body();

    var replay = service.reserve(admin, key, request);

    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.body().packageId()).isEqualTo(first.packageId());
    verify(facility, times(1)).reserve(any());
    verify(quotes, times(1)).price(any(), any());
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
    assertThatThrownBy(() -> service.reserve(admin, key, request(courtA)))
        .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void facilityConflictCreatesNoBookingAndRetryWithTheSameKeyStartsANewAttempt() {
    UUID key = UUID.randomUUID();
    doThrow(new FullCourtFacility.Refused(409, "LOCK_OVERLAP")).when(facility).reserve(any());

    assertThatThrownBy(() -> service.reserve(admin, key, request))
        .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    assertThat(count("SELECT count(*) FROM bookings")).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("FAILED");
    var burnt = org.mockito.ArgumentCaptor.forClass(UUID.class);
    verify(facility).rollback(burnt.capture());

    doAnswer(call -> reservation(((FullCourtFacility.Reserve) call.getArgument(0)).reservationId(), "RESERVED"))
        .when(facility)
        .reserve(any());
    var retry = service.reserve(admin, key, request);

    assertThat(retry.status()).isEqualTo(201);
    assertThat(count("SELECT count(*) FROM full_court_packages")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT attempt FROM full_court_packages", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT reservation_id FROM full_court_packages", UUID.class)).isNotEqualTo(burnt.getValue());
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
  }

  @Test
  void facilityOutageOnReserveRollsBackAndAnswers503() {
    doThrow(new FullCourtFacility.Unavailable("down", null)).when(facility).reserve(any());

    assertThatThrownBy(() -> service.reserve(admin, UUID.randomUUID(), request))
        .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    verify(facility).rollback(any());
    assertThat(count("SELECT count(*) FROM bookings")).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("FAILED");
  }

  @Test
  void unconfirmedRollbackKeepsTheCommandForTheReconcilerWhichThenReleasesIt() {
    doThrow(new FullCourtFacility.Unavailable("down", null)).when(facility).reserve(any());
    doThrow(new FullCourtFacility.Unavailable("still down", null)).when(facility).rollback(any());

    assertThatThrownBy(() -> service.reserve(admin, UUID.randomUUID(), request)).isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("RESERVING");
    assertThat(service.reconcile()).isZero(); // too fresh to touch

    jdbc.update("UPDATE full_court_packages SET updated_at = now() - interval '5 minutes'");
    assertThat(service.reconcile()).isZero(); // facility still down: deferred, not lost
    doAnswer(call -> reservation(call.getArgument(0), "CANCELLED")).when(facility).rollback(any());
    assertThat(service.reconcile()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("FAILED");
  }

  @Test
  void walkInBookingThatGotThereFirstWinsAndTheFacilityReservationIsRolledBack() {
    seedExistingBooking(courtB, "08:30", "09:00");

    assertThatThrownBy(() -> service.reserve(admin, UUID.randomUUID(), request))
        .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));

    assertThat(count("SELECT count(*) FROM full_court_package_bookings")).isZero();
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1); // only the walk-in
    assertThat(count("SELECT count(*) FROM booking_slots WHERE court_id = ?", courtA)).isZero(); // all or nothing
    verify(facility).rollback(any());
    verify(facility, never()).commit(any());
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("FAILED");
  }

  @Test
  void unconfirmedCommitLeavesBookedAndAReplayFinishesIt() {
    UUID key = UUID.randomUUID();
    doThrow(new FullCourtFacility.Unavailable("timeout", null)).when(facility).commit(any());

    var first = service.reserve(admin, key, request);

    assertThat(first.status()).isEqualTo(202);
    assertThat(first.body().state()).isEqualTo("BOOKED");
    assertThat(first.body().lockState()).isEqualTo("PENDING_COMMIT");
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
    verify(facility, times(3)).commit(any());
    verify(facility, never()).rollback(any());

    doAnswer(call -> reservation(call.getArgument(0), "COMMITTED")).when(facility).commit(any());
    var replay = service.reserve(admin, key, request);

    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.body().state()).isEqualTo("COMMITTED");
    verify(facility, times(1)).reserve(any());
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
  }

  @Test
  void reconcilerCommitsStaleBookedPackages() {
    doThrow(new FullCourtFacility.Unavailable("timeout", null)).when(facility).commit(any());
    service.reserve(admin, UUID.randomUUID(), request);
    assertThat(service.reconcile()).isZero();

    jdbc.update("UPDATE full_court_packages SET updated_at = now() - interval '5 minutes'");
    assertThat(service.reconcile()).isZero(); // still failing: stays BOOKED, retried after the stale window
    jdbc.update("UPDATE full_court_packages SET updated_at = now() - interval '5 minutes'");
    doAnswer(call -> reservation(call.getArgument(0), "COMMITTED")).when(facility).commit(any());
    assertThat(service.reconcile()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT state FROM full_court_packages", String.class)).isEqualTo("COMMITTED");
  }

  @Test
  void commitRefusedByFacilityMarksTheLockLostButKeepsTheBookingsAndTheirSlots() {
    doThrow(new FullCourtFacility.Refused(409, "RESERVATION_EXPIRED")).when(facility).commit(any());

    var result = service.reserve(admin, UUID.randomUUID(), request);

    assertThat(result.status()).isEqualTo(202);
    assertThat(result.body().state()).isEqualTo("LOCK_LOST");
    assertThat(result.body().lockState()).isEqualTo("LOST");
    assertThat(count("SELECT count(*) FROM booking_slots WHERE active AND court_id IN (?,?)", courtA, courtB)).isEqualTo(4);
    verify(facility, times(1)).commit(any());
  }

  @Test
  void sameKeyInParallelProducesOnePackageAndOneSetOfBookings() throws Exception {
    doAnswer(
            call -> {
              Thread.sleep(300); // keep the first caller inside the saga while the second arrives
              return reservation(((FullCourtFacility.Reserve) call.getArgument(0)).reservationId(), "RESERVED");
            })
        .when(facility)
        .reserve(any());
    UUID key = UUID.randomUUID();
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    var outcomes = new ArrayList<Future<Integer>>();
    for (int n = 0; n < 2; n++) {
      outcomes.add(
          pool.submit(
              () -> {
                gate.await();
                try {
                  return service.reserve(admin, key, request).status();
                } catch (ResponseStatusException e) {
                  return e.getStatusCode().value();
                }
              }));
    }
    gate.countDown();
    var codes = new ArrayList<Integer>();
    for (var outcome : outcomes) codes.add(outcome.get());
    pool.shutdown();

    assertThat(codes).contains(201);
    assertThat(codes).allMatch(code -> code == 201 || code == 409 || code == 200);
    assertThat(count("SELECT count(*) FROM full_court_packages")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
    verify(facility, times(1)).reserve(any());
  }

  @Test
  void twoPackagesOnTheSameWindowCannotBothSucceedEvenIfFacilityGrantedBoth() throws Exception {
    // The facility mock happily reserves both (as if its lock had been lost): the booking DB must still arbitrate.
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    var outcomes = new ArrayList<Future<Integer>>();
    for (int n = 0; n < 2; n++) {
      UUID key = UUID.randomUUID();
      outcomes.add(
          pool.submit(
              () -> {
                gate.await();
                try {
                  return service.reserve(admin, key, request).status();
                } catch (ResponseStatusException e) {
                  return e.getStatusCode().value();
                }
              }));
    }
    gate.countDown();
    var codes = new ArrayList<Integer>();
    for (var outcome : outcomes) codes.add(outcome.get());
    pool.shutdown();

    assertThat(codes).containsExactlyInAnyOrder(201, 409);
    assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM booking_slots WHERE active AND court_id IN (?,?)", courtA, courtB)).isEqualTo(4);
    verify(facility, times(1)).rollback(any());
    verify(facility, times(1)).commit(any());
  }

  @Test
  void controllerMapsStatusesAndRequiresAnAdminAndAnIdempotencyKey() throws Exception {
    String body =
        """
        {"branchId":7,"courtIds":[%d,%d],"date":"2030-01-02","startTime":"08:00","endTime":"09:00",
         "contactName":"Giai dau noi bo","contactPhone":"0901234567"}
        """
            .formatted(courtA, courtB);
    var adminJwt = jwt().jwt(j -> j.subject("10").claim("roles", List.of("ADMIN")));
    mvc.perform(post("/api/admin/bookings/full-court").with(adminJwt).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/admin/bookings/full-court").contentType(MediaType.APPLICATION_JSON).content(body)
            .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/admin/bookings/full-court")
                .with(jwt().jwt(j -> j.subject("11").claim("roles", List.of("CUSTOMER"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/bookings/full-court")
                .with(adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("0901234567", "abc"))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/admin/bookings/full-court")
                .with(adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("COMMITTED"))
        .andExpect(jsonPath("$.total").value(35000.04))
        .andExpect(jsonPath("$.bookings.length()").value(2));
  }
}
