package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class OnlineBooking {
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private final JdbcTemplate jdbc;
  private final OnlineBookingDependencies upstream;
  private final CounterDependencies facility;
  private final BookingQueries queries;
  private final Clock clock;
  /** Idempotency namespace shared by all guests; real user ids never reach this value. */
  static final long GUEST_ACTOR = Long.MAX_VALUE;
  private final SecureRandom random = new SecureRandom();

  OnlineBooking(
      JdbcTemplate jdbc,
      OnlineBookingDependencies upstream,
      CounterDependencies facility,
      BookingQueries queries,
      Clock clock) {
    this.jdbc = jdbc;
    this.upstream = upstream;
    this.facility = facility;
    this.queries = queries;
    this.clock = clock;
  }

  @Transactional(timeout = 15)
  public BookingView create(Jwt jwt, UUID key, Request request) {
    // Guest (no JWT): authority is the hold token and the phone sealed in the hold, like the old
    // monolith phone-only guest booking. Registered customers keep the JWT path.
    boolean guest = jwt == null;
    long actor = guest ? GUEST_ACTOR : BookingQueries.actor(jwt);
    var customer = guest ? null : upstream.customer(jwt);
    String holdToken = request.holdToken().toString();
    if (request.slotIds().size() != new HashSet<>(request.slotIds()).size())
      throw bad("Duplicate slots");
    var ids = request.slotIds().stream().sorted().toList();
    String fingerprint =
        hash(request.holdToken() + ":" + request.courtId() + ":" + request.date() + ":" + ids);
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "online:" + actor + ":" + key);
    var previous =
        jdbc.queryForList(
            "SELECT fingerprint,booking_id FROM online_booking_commands WHERE actor_id=? AND"
                + " command_key=?",
            actor,
            key);
    if (!previous.isEmpty()) {
      if (!fingerprint.equals(previous.getFirst().get("fingerprint")))
        throw conflict("Idempotency key changed");
      return queries.detail(
          ((Number) previous.getFirst().get("booking_id")).longValue(), jwt, holdToken);
    }
    if (request.date().getYear() < 1
        || request.date().getYear() > 9999
        || request.date().isBefore(LocalDate.now(clock.withZone(ZONE))))
      throw bad("Unsupported booking date");
    var context = upstream.context(request.courtId());
    var all = facility.canonicalSlots(request.courtId(), request.date());
    var slots =
        all.stream()
            .filter(s -> ids.contains(s.slotId()))
            .sorted(Comparator.comparing(CounterDependencies.Slot::startTime))
            .toList();
    if (slots.size() != ids.size() || slots.stream().anyMatch(s -> !"AVAILABLE".equals(s.status())))
      throw conflict("Selected slots unavailable");
    for (int n = 0; n < slots.size(); n++) {
      var slot = slots.get(n);
      if (!request
          .date()
          .atTime(slot.startTime())
          .atZone(ZONE)
          .toInstant()
          .isAfter(clock.instant())) throw conflict("Selected slot already started");
      if (n > 0 && !slots.get(n - 1).endTime().equals(slot.startTime()))
        throw bad("Slots must be contiguous");
    }
    BigDecimal total =
        slots.stream()
            .map(CounterDependencies.Slot::price)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.signum() <= 0 || total.compareTo(new BigDecimal("99999999.99")) > 0)
      throw bad("Unsupported booking total");
    // Same namespace as StaffCounter; all durable writers serialize per court/day.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "counter:" + request.courtId() + ":" + request.date());
    var occupied = SlotOccupancy.active(jdbc, request.courtId(), request.date());
    for (var slot : slots)
      if (occupied.stream().anyMatch(s -> s.overlaps(slot.startTime(), slot.endTime())))
        throw conflict("Selected slots already booked");
    var lease = upstream.claim(actor, key, request);
    if (guest && lease.guestPhone() == null) throw bad("Phone number is required for guest booking");
    if (!lease.expiresAt().isAfter(clock.instant().plusSeconds(20)))
      throw conflict("Hold too close to expiration");
    long id = 0;
    try {
      for (int attempt = 0; attempt < 10 && id == 0; attempt++) {
        String code = "FC" + String.format(Locale.ROOT, "%08d", random.nextInt(100000000));
        var created =
            jdbc.queryForList(
                """
INSERT INTO bookings(court_id,customer_id,guest_phone,date,total_price,booking_code,branch_id,branch_name,
branch_address,branch_phone,court_name,customer_name,hold_token_hash,hold_expires_at,payment_status)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING') ON CONFLICT(booking_code) DO NOTHING RETURNING id
""",
                request.courtId(),
                guest ? null : actor,
                lease.guestPhone(),
                request.date(),
                total,
                code,
                context.branchId(),
                context.branchName(),
                context.branchAddress(),
                context.branchPhone(),
                context.courtName(),
                guest ? null : customer.fullName(),
                lease.holdHash(),
                Timestamp.from(lease.expiresAt()));
        if (!created.isEmpty()) id = ((Number) created.getFirst().get("id")).longValue();
      }
      if (id == 0) throw OnlineBookingDependencies.unavailable();
      for (var slot : slots)
        jdbc.update(
            "INSERT INTO"
                + " booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking,source_slot_id)"
                + " VALUES (?,?,?,?,?,?,?)",
            id,
            request.courtId(),
            request.date(),
            slot.startTime(),
            slot.endTime(),
            slot.price(),
            slot.slotId());
      jdbc.update(
          "INSERT INTO online_booking_commands(actor_id,command_key,fingerprint,booking_id) VALUES"
              + " (?,?,?,?)",
          actor,
          key,
          fingerprint,
          id);
      jdbc.update(
          "INSERT INTO online_hold_releases(booking_id,hold_hash) VALUES (?,?)",
          id,
          lease.holdHash());
    } catch (DuplicateKeyException e) {
      throw conflict("Hold or slot already committed");
    }
    return queries.detail(id, jwt, holdToken);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  record Request(
      @NotNull UUID holdToken,
      @Positive long courtId,
      @NotNull LocalDate date,
      @NotEmpty @Size(max = 48) List<@NotNull @Positive Long> slotIds) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
      throw new IllegalArgumentException("Unsupported booking field");
    }

    @Override
    public String toString() {
      return "OnlineBookingRequest[REDACTED]";
    }
  }
}

@RestController
class OnlineBookingController {
  private final OnlineBooking service;

  OnlineBookingController(OnlineBooking service) {
    this.service = service;
  }

  @PostMapping("/api/bookings")
  ResponseEntity<BookingView> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody OnlineBooking.Request request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(service.create(jwt, key, request));
  }
}
