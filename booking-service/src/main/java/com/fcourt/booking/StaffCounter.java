package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
class StaffCounter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal MAX = new BigDecimal("99999999.99");
    private final JdbcTemplate jdbc;
    private final StaffAuthority authority;
    private final CounterDependencies dependencies;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] credential;
    private final BookingPosCharges posCharges;
    private final SecureRandom random = new SecureRandom();

    StaffCounter(
            JdbcTemplate jdbc,
            StaffAuthority authority,
            CounterDependencies dependencies,
            ObjectMapper json,
            Clock clock,
            BookingPosCharges posCharges,
            @Value("${STAFF_BOOKING_SERVICE_TOKEN:}") String credential) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.dependencies = dependencies;
        this.json = json;
        this.clock = clock;
        this.posCharges = posCharges;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
    }

    Schedule schedule(Jwt jwt, long branch, LocalDate start, LocalDate end) {
        authority.require(jwt, branch, BookingQueries.actor(jwt));
        if (start == null
                || end == null
                || end.isBefore(start)
                || Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 6)
            throw bad();
        var courts = dependencies.courts(branch, jwt);
        var result = new ArrayList<Court>();
        for (var court : courts) {
            var sessions =
                    jdbc.query(
                            """
SELECT b.id,b.date,b.status,b.customer_name,b.guest_phone,min(s.slot_start) AS first,
  CASE WHEN bool_or(s.slot_end='00:00'::time) THEN '00:00'::time ELSE max(s.slot_end) END AS last
FROM bookings b JOIN booking_slots s ON s.booking_id=b.id
WHERE b.branch_id=? AND b.court_id=? AND b.date BETWEEN ? AND ?
  AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
GROUP BY b.id ORDER BY b.date,min(s.slot_start),b.id
""",
                            (r, n) ->
                                    new Session(
                                            r.getLong("id"),
                                            r.getObject("date", LocalDate.class),
                                            r.getObject("first", LocalTime.class),
                                            r.getObject("last", LocalTime.class),
                                            r.getString("status"),
                                            r.getString("customer_name"),
                                            r.getString("guest_phone")),
                            branch,
                            court.id(),
                            start,
                            end);
            if (sessions.size() > 3360) throw CounterDependencies.unavailable();
            result.add(new Court(court.id(), court.name(), sessions));
        }
        return new Schedule(branch, result);
    }

    List<Long> busy(long branch, long court, LocalDate date, List<Long> ids, Jwt jwt) {
        if (dependencies.courts(branch, jwt).stream().noneMatch(c -> c.id() == court))
            throw missing();
        var selected = select(dependencies.canonicalSlots(court, date), ids, false);
        var occupied = SlotOccupancy.active(jdbc,court,date);
        return selected.stream()
                .filter(s -> occupied.stream().anyMatch(o -> o.overlaps(s.startTime(),s.endTime())))
                .map(CounterDependencies.Slot::slotId)
                .toList();
    }

    Occupancy occupancy(long court, LocalDate date) {
        if (court <= 0 || date == null) throw bad();
        var canonical = dependencies.canonicalSlots(court, date);
        var occupied = SlotOccupancy.active(jdbc,court,date);
        return new Occupancy(
                court,
                date,
                canonical.stream()
                        .filter(s -> occupied.stream().anyMatch(o -> o.overlaps(s.startTime(),s.endTime())))
                        .map(CounterDependencies.Slot::slotId)
                        .toList());
    }

    @Transactional(timeout = 15)
    WalkInResult create(Jwt jwt, String key, String serviceToken, WalkIn request) {
        authenticate(jwt, request.branchId(), request.actorId(), serviceToken);
        String fingerprint = identity(request);
        lock(request.actorId(), key, "walk-in");
        var replay = replay(request.actorId(), key, fingerprint, WalkInResult.class);
        if (replay != null) return replay;
        if (!LocalDate.now(clock.withZone(ZONE)).equals(request.date())
                || request.checkedInAt() == null
                || Math.abs(Duration.between(request.checkedInAt(), clock.instant()).toSeconds())
                        > 60
                || !"CASH".equals(request.paymentMethod())
                || request.guestPhone() == null
                || !request.guestPhone().matches("\\+?[0-9]{9,15}")
                || request.guestName() != null
                        && (request.guestName().isBlank() || request.guestName().length() > 150))
            throw bad();
        var court =
                dependencies.courts(request.branchId(), jwt).stream()
                        .filter(c -> c.id() == request.courtId() && "ACTIVE".equals(c.status()))
                        .findFirst()
                        .orElseThrow(StaffCounter::missing);
        select(dependencies.slots(request.courtId(), request.date()), request.slotIds(), true);
        var slots =
                select(
                        dependencies.canonicalSlots(request.courtId(), request.date()),
                        request.slotIds(),
                        true);
        validateQuote(request.quote(), slots);
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                "counter:" + request.courtId() + ":" + request.date());
        var occupied = SlotOccupancy.active(jdbc,request.courtId(),request.date());
        for (var slot : slots)
            if (occupied.stream().anyMatch(o -> o.overlaps(slot.startTime(),slot.endTime()))) throw conflict();
        dependencies.reserve(
                request.branchId(),
                request.actorId(),
                request.courtId(),
                request.date(),
                request.slotIds(),
                key,
                jwt);
        long id = 0;
        String code = "";
        for (int attempt = 0; attempt < 10 && id == 0; attempt++) {
            code = "FC" + String.format(Locale.ROOT, "%08d", random.nextInt(100000000));
            var inserted =
                    jdbc.queryForList(
                            """
INSERT INTO bookings(court_id,guest_phone,date,status,total_price,booking_code,branch_id,court_name,customer_name,checkin_code,checked_in_at,payment_status)
VALUES (?,?,?,'CHECKED_IN',?,?,?,?,?,?,?,'PAID') ON CONFLICT(booking_code) DO NOTHING RETURNING id
""",
                            request.courtId(),
                            request.guestPhone(),
                            request.date(),
                            request.quote().totalAmount(),
                            code,
                            request.branchId(),
                            court.name(),
                            request.guestName(),
                            UUID.randomUUID().toString(),
                            java.sql.Timestamp.from(request.checkedInAt()));
            if (!inserted.isEmpty()) id = ((Number) inserted.getFirst().get("id")).longValue();
        }
        if (id == 0) throw CounterDependencies.unavailable();
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
        var quote = request.quote();
        var invoice =
                new Invoice(
                        "CASH-" + id,
                        request.actorId(),
                        quote.baseAmount(),
                        quote.surchargeAmount(),
                        quote.totalAmount(),
                        quote.policyVersion(),
                        quote.items());
        var result =
                new WalkInResult(
                        id,
                        code,
                        request.branchId(),
                        request.courtId(),
                        request.date(),
                        "CHECKED_IN",
                        "CASH",
                        quote.totalAmount(),
                        request.checkedInAt(),
                        invoice);
        record(request.actorId(), key, fingerprint, result);
        return result;
    }

    @Transactional(readOnly = true)
    WalkInResult reconcile(Jwt jwt, String key, String serviceToken, WalkIn request) {
        authenticate(jwt, request.branchId(), request.actorId(), serviceToken);
        validateKey(request.actorId(), key, "walk-in");
        var result = replay(request.actorId(), key, identity(request), WalkInResult.class);
        if (result == null) throw missing();
        return result;
    }

    CheckoutPreview preview(Jwt jwt, long id, long branch, long actor) {
        authority.require(jwt, branch, actor);
        var booking = booking(id, branch, false);
        if (!"CHECKED_IN".equals(booking.status())) throw conflict();
        BigDecimal fee = overtime(booking, clock.instant());
        var posAmount = posCharges.pending(id);
        return new CheckoutPreview(
                id, branch, fee, amount(booking.total().add(fee).add(posAmount)), posAmount);
    }

    @Transactional(timeout = 15)
    CheckoutResult checkout(Jwt jwt, long id, String key, String serviceToken, Checkout request) {
        authenticate(jwt, request.branchId(), request.actorId(), serviceToken);
        lock(request.actorId(), key, "checkout");
        String fingerprint =
                hash(
                        Arrays.asList(
                                id,
                                request.branchId(),
                                request.expectedSurcharge(),
                                request.expectedTotal()));
        var previous = replay(request.actorId(), key, fingerprint, CheckoutResult.class);
        if (previous != null) return previous;
        var booking = booking(id, request.branchId(), true);
        if (!"CHECKED_IN".equals(booking.status())
                || request.completedAt() == null
                || Math.abs(Duration.between(request.completedAt(), clock.instant()).toSeconds())
                        > 60
                || booking.checkedInAt() == null
                || request.completedAt().isBefore(booking.checkedInAt())) throw conflict();
        BigDecimal fee = overtime(booking, request.completedAt());
        if (request.expectedSurcharge() == null
                || amount(request.expectedSurcharge()).compareTo(fee) != 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Checkout fee changed; preview and confirm again");
        BigDecimal posAmount = posCharges.pending(id);
        BigDecimal total = amount(booking.total().add(fee).add(posAmount));
        if (request.expectedTotal() == null
                || amount(request.expectedTotal()).compareTo(total) != 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Checkout total changed; preview and confirm again");
        jdbc.update(
                "UPDATE bookings SET status='COMPLETED',completed_at=?,updated_at=now() WHERE id=?",
                java.sql.Timestamp.from(request.completedAt()),
                id);
        jdbc.update(
                "INSERT INTO"
                    + " booking_cash_settlements(booking_id,actor_id,surcharge_amount,total_amount,completed_at,policy_version)"
                    + " VALUES (?,?,?,?,?,'overtime-slot-v1')",
                id,
                request.actorId(),
                fee,
                total,
                java.sql.Timestamp.from(request.completedAt()));
        // Release only the unused portion; preserve past occupancy and history.
        jdbc.update(
                "UPDATE booking_slots SET active=false WHERE booking_id=? AND"
                    + " (booking_date+slot_end+CASE WHEN slot_end='00:00'::time THEN interval '1"
                    + " day' ELSE interval '0 day' END) AT TIME ZONE 'Asia/Ho_Chi_Minh' > ?",
                id,
                java.sql.Timestamp.from(request.completedAt()));
        jdbc.update(
                "UPDATE booking_cash_settlements SET pos_amount=? WHERE booking_id=?",
                posAmount,
                id);
        posCharges.enqueue(id, request.branchId(), request.completedAt(), key);
        var result =
                new CheckoutResult(
                        id, request.branchId(), "COMPLETED", request.completedAt(), fee, total);
        record(request.actorId(), key, fingerprint, result);
        return result;
    }

    private BigDecimal overtime(Booking booking, Instant completed) {
        var last =
                jdbc.query(
                        "SELECT booking_date,slot_end FROM booking_slots WHERE booking_id=? ORDER"
                                + " BY booking_date DESC,CASE WHEN slot_end='00:00'::time THEN"
                                + " '24:00'::time ELSE slot_end END DESC LIMIT 1",
                        (r, n) ->
                                r.getObject(1, LocalDate.class)
                                        .plusDays(
                                                r.getObject(2, LocalTime.class)
                                                                .equals(LocalTime.MIDNIGHT)
                                                        ? 1
                                                        : 0)
                                        .atTime(r.getObject(2, LocalTime.class))
                                        .atZone(ZONE)
                                        .toInstant(),
                        booking.id());
        if (last.isEmpty()) throw conflict();
        Instant end = last.getFirst();
        if (!completed.isAfter(end)) return BigDecimal.ZERO.setScale(2);
        var elapsed = Duration.between(end, completed);
        if (elapsed.compareTo(Duration.ofDays(1)) > 0) throw conflict();
        long periods = (elapsed.toNanos() + 1800000000000L - 1) / 1800000000000L;
        if (periods > 48)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Overtime exceeds one day; manual reconciliation required");
        BigDecimal fee = BigDecimal.ZERO;
        var days = new HashMap<LocalDate, List<CounterDependencies.Slot>>();
        for (long index = 0; index < periods; index++) {
            var start = end.plusSeconds(index * 1800).atZone(ZONE);
            var list =
                    days.computeIfAbsent(
                            start.toLocalDate(),
                            d -> dependencies.canonicalSlots(booking.court(), d));
            var rate =
                    list.stream()
                            .filter(s -> s.startTime().equals(start.toLocalTime()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Missing overtime slot rate; manual"
                                                            + " reconciliation required"));
            fee = fee.add(rate.price());
        }
        return amount(fee);
    }

    private Booking booking(long id, long branch, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT id,court_id,status,total_price,checked_in_at FROM bookings WHERE"
                                + " id=? AND branch_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Booking(
                                        r.getLong(1),
                                        r.getLong(2),
                                        r.getString(3),
                                        r.getBigDecimal(4),
                                        r.getTimestamp(5) == null
                                                ? null
                                                : r.getTimestamp(5).toInstant()),
                        id,
                        branch);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private List<CounterDependencies.Slot> select(
            List<CounterDependencies.Slot> all, List<Long> ids, boolean available) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 48
                || new HashSet<>(ids).size() != ids.size()) throw bad();
        var selected =
                all.stream()
                        .filter(s -> ids.contains(s.slotId()))
                        .sorted(Comparator.comparing(CounterDependencies.Slot::startTime))
                        .toList();
        if (selected.size() != ids.size()
                || available && selected.stream().anyMatch(s -> !"AVAILABLE".equals(s.status())))
            throw conflict();
        for (int n = 1; n < selected.size(); n++)
            if (!selected.get(n - 1).endTime().equals(selected.get(n).startTime())) throw bad();
        return selected;
    }

    private void validateQuote(Quote quote, List<CounterDependencies.Slot> slots) {
        if (quote == null
                || quote.items() == null
                || quote.items().size() != slots.size()
                || quote.policyVersion() == null
                || quote.policyVersion().isBlank()
                || quote.policyVersion().length() > 64) throw bad();
        BigDecimal base =
                slots.stream()
                        .map(CounterDependencies.Slot::price)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount(quote.baseAmount()).compareTo(base) != 0
                || amount(quote.baseAmount().add(amount(quote.surchargeAmount())))
                                .compareTo(amount(quote.totalAmount()))
                        != 0) throw conflict();
        var ids = new HashSet<Long>();
        for (var item : quote.items()) {
            if (item == null || !ids.add(item.slotId())) throw bad();
            var actual =
                    slots.stream()
                            .filter(s -> s.slotId() == item.slotId())
                            .findFirst()
                            .orElseThrow(StaffCounter::bad);
            if (!actual.startTime().equals(item.startTime())
                    || !actual.endTime().equals(item.endTime())
                    || actual.price().compareTo(amount(item.price())) != 0) throw conflict();
        }
    }

    private BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2 || value.compareTo(MAX) > 0)
            throw bad();
        return value.setScale(2);
    }

    private void authenticate(Jwt jwt, long branch, long actor, String supplied) {
        if (credential.length < 32) throw CounterDependencies.unavailable();
        if (supplied == null
                || !MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Staff service credential required");
        authority.require(jwt, branch, actor);
    }

    private void validateKey(long actor, String key, String kind) {
        try {
            if (!key.startsWith("staff:" + kind + ":" + actor + ":"))
                throw new IllegalArgumentException();
            UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
        } catch (Exception e) {
            throw bad();
        }
    }

    private void lock(long actor, String key, String kind) {
        validateKey(actor, key, kind);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", actor + ":" + key);
    }

    private String identity(WalkIn request) {
        return hash(
                Arrays.asList(
                        request.branchId(),
                        request.courtId(),
                        request.date(),
                        request.slotIds(),
                        request.guestPhone(),
                        request.guestName()));
    }

    private String hash(Object request) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(request)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode counter command");
        }
    }

    private <T> T replay(long actor, String key, String fingerprint, Class<T> type) {
        var rows =
                jdbc.queryForList(
                        "SELECT fingerprint,result::text AS result FROM booking_staff_commands"
                                + " WHERE actor_id=? AND command_key=?",
                        actor,
                        key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!fingerprint.equals(row.get("fingerprint"))) throw conflict();
        try {
            return json.readValue((String) row.get("result"), type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored command");
        }
    }

    private void record(long actor, String key, String fingerprint, Object result) {
        try {
            jdbc.update(
                    "INSERT INTO booking_staff_commands(actor_id,command_key,fingerprint,result)"
                            + " VALUES (?,?,?,?::jsonb)",
                    actor,
                    key,
                    fingerprint,
                    json.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist counter command");
        }
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid counter command");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Booking or slot conflict");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking or court not found");
    }

    record Occupancy(long courtId, LocalDate date, List<Long> bookedSlotIds) {}

    record Schedule(long branchId, List<Court> courts) {}

    record Court(long courtId, String courtName, List<Session> slots) {}

    record Session(
            long bookingId,
            LocalDate date,
            LocalTime slotStart,
            LocalTime slotEnd,
            String status,
            String customerName,
            String guestPhone) {}

    record Item(long slotId, LocalTime startTime, LocalTime endTime, BigDecimal price) {}

    record Quote(
            BigDecimal baseAmount,
            BigDecimal surchargeAmount,
            BigDecimal totalAmount,
            String policyVersion,
            List<Item> items) {}

    record Invoice(
            String receiptCode,
            long cashierUserId,
            BigDecimal baseAmount,
            BigDecimal surchargeAmount,
            BigDecimal totalAmount,
            String policyVersion,
            List<Item> items) {}

    record WalkIn(
            long branchId,
            long actorId,
            long courtId,
            LocalDate date,
            List<Long> slotIds,
            String guestPhone,
            String paymentMethod,
            Instant checkedInAt,
            Quote quote,
            String guestName) {}

    record WalkInResult(
            long bookingId,
            String bookingCode,
            long branchId,
            long courtId,
            LocalDate date,
            String status,
            String paymentMethod,
            BigDecimal amount,
            Instant checkedInAt,
            Invoice invoice) {}

    record Checkout(
            long branchId,
            long actorId,
            Instant completedAt,
            BigDecimal expectedSurcharge,
            BigDecimal expectedTotal) {}

    record CheckoutPreview(
            long bookingId,
            long branchId,
            BigDecimal surchargeAmount,
            BigDecimal totalAmount,
            BigDecimal posAmount) {}

    record CheckoutResult(
            long bookingId,
            long branchId,
            String status,
            Instant completedAt,
            BigDecimal surchargeAmount,
            BigDecimal totalAmount) {}

    private record Booking(
            long id, long court, String status, BigDecimal total, Instant checkedInAt) {}
}
