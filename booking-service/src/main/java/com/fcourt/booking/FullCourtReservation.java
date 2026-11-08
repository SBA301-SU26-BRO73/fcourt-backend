package com.fcourt.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Atomic full-court reservation saga (SCRUM-242 for SCRUM-114): facility reserve, then member bookings, then facility
 * commit, with compensation.
 *
 * <ol>
 *   <li>Price on the server (the #105 quote) before anything is stored; the priced slots and totals are frozen in
 *       {@code full_court_packages} so a replay never reprices.
 *   <li>Store the command (state RESERVING) with the reservation id of this attempt.
 *   <li>facility-service reserve (locks every court for the window, visible to all consumers as OUT_OF_SERVICE).
 *   <li>One booking-DB transaction: advisory lock {@code counter:<court>:<date>} per court (the namespace the counter and
 *       online paths use), re-check {@code booking_slots}, insert one booking per court, its slots (guarded by the unique
 *       index on active slots) and the package link, and flip the command to BOOKED. A walk-in or online booking that got
 *       there first makes this step fail, so there is no double booking whatever facility says.
 *   <li>facility-service commit. If it cannot be confirmed the package stays BOOKED and {@link #reconcile()} finishes it.
 * </ol>
 *
 * Any failure before BOOKED rolls the facility reservation back (compensation) and marks the command FAILED; a rollback
 * that cannot be confirmed leaves RESERVING for the reconciler. Only success is memoised: retrying a FAILED key starts a
 * new attempt with a new reservation id. Deposit and e-contract are out of scope of SCRUM-114's acceptance criteria.
 */
@Service
class FullCourtReservation {
    private static final Logger log = LoggerFactory.getLogger(FullCourtReservation.class);
    private static final BigDecimal MAX = new BigDecimal("99999999.99");
    static final int RESERVING_STALE_SECONDS = 60;
    static final int BOOKED_STALE_SECONDS = 10;
    private static final int TTL_SECONDS = 120;
    private static final int ATTEMPTS = 3;

    record Request(@Positive long branchId, @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> courtIds,
            @NotNull LocalDate date, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
            @NotBlank @Size(max = 150) String contactName,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String contactPhone) {
        FullCourtQuote.Request pricing() {
            return new FullCourtQuote.Request(branchId, courtIds, date, startTime, endTime);
        }
    }

    record BookingRef(long bookingId, long courtId, String bookingCode, BigDecimal totalAmount) {}

    /** {@code lockState}: COMMITTED, PENDING_COMMIT (booked, facility commit still being confirmed) or LOST. */
    record Package(UUID packageId, String state, String lockState, long branchId, List<Long> courtIds, LocalDate date,
            LocalTime startTime, LocalTime endTime, BigDecimal courtHours, BigDecimal subtotal,
            BigDecimal discountPercent, BigDecimal discountAmount, BigDecimal total, String currency,
            String contactName, String contactPhone, List<BookingRef> bookings) {}

    record Result(int status, Package body) {}

    private record Existing(UUID id, String fingerprint, String state) {}

    private record Slice(long courtId, long slotId, LocalTime start, LocalTime end, BigDecimal price) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FullCourtQuote quotes;
    private final FullCourtFacility facility;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    FullCourtReservation(JdbcTemplate jdbc, TransactionTemplate tx, FullCourtQuote quotes, FullCourtFacility facility,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.quotes = quotes;
        this.facility = facility;
        this.json = json;
    }

    Result reserve(Jwt jwt, UUID key, Request request) {
        FullCourtQuote.requireAdmin(jwt);
        long actor = BookingQueries.actor(jwt);
        String fingerprint = fingerprint(request);

        Existing existing = existing(actor, key);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw conflict("Idempotency key was already used for a different full-court request");
            }
            switch (existing.state()) {
                case "COMMITTED", "LOCK_LOST" -> {
                    return new Result(200, view(existing.id()));
                }
                case "BOOKED" -> {
                    return finish(existing.id(), false);
                }
                case "RESERVING" -> throw conflict("This full-court request is still being processed; retry shortly");
                default -> { /* FAILED: a retry starts a new attempt below */ }
            }
        }

        // Server pricing and ownership/availability checks first; a failure here stores nothing.
        var priced = quotes.price(request.pricing(), jwt);
        UUID reservationId = UUID.randomUUID();
        UUID packageId = store(actor, key, fingerprint, request, priced, reservationId, existing);

        // Step 3: facility reserve.
        try {
            facility.reserve(new FullCourtFacility.Reserve(reservationId, request.branchId(), actor, request.courtIds(),
                    request.date().atTime(request.startTime()).toString(), request.date().atTime(request.endTime()).toString(),
                    TTL_SECONDS, null));
        } catch (FullCourtFacility.Refused refused) {
            throw abort(packageId, reservationId, "facility " + refused.status + " " + refused.code, refused);
        } catch (FullCourtFacility.Unavailable unavailable) {
            throw abort(packageId, reservationId, "facility unavailable", unavailable);
        }
        jdbc.update("UPDATE full_court_packages SET updated_at = now() WHERE id = ?", packageId);

        // Step 4: member bookings, atomically, against the booking DB's own arbiter.
        try {
            tx.executeWithoutResult(status -> book(packageId, reservationId, actor, request, priced));
        } catch (ResponseStatusException conflict) {
            throw abort(packageId, reservationId, "booking " + conflict.getStatusCode().value(), conflict);
        } catch (DuplicateKeyException duplicate) {
            throw abort(packageId, reservationId, "booking slots taken", conflict("Selected court window was just booked"));
        } catch (RuntimeException error) {
            log.warn("Full-court booking step failed for package {}", packageId, error);
            throw abort(packageId, reservationId, "booking error", unavailable("Could not record the full-court booking"));
        }
        // Step 5: facility commit.
        return finish(packageId, true);
    }

    /** Idempotent commit of a BOOKED package; 201/200 when confirmed, 202 while it is still pending or lost. */
    private Result finish(UUID packageId, boolean created) {
        UUID reservationId = jdbc.queryForObject("SELECT reservation_id FROM full_court_packages WHERE id = ?", UUID.class, packageId);
        String outcome = commitOnce(packageId, reservationId);
        Package view = view(packageId);
        return switch (outcome) {
            case "COMMITTED" -> new Result(created ? 201 : 200, view);
            default -> new Result(202, view);
        };
    }

    /** Returns the resulting state: COMMITTED, LOCK_LOST or BOOKED (commit not confirmed, will be retried). */
    private String commitOnce(UUID packageId, UUID reservationId) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                facility.commit(reservationId);
                jdbc.update("UPDATE full_court_packages SET state = 'COMMITTED', updated_at = now() WHERE id = ? AND state = 'BOOKED'", packageId);
                return "COMMITTED";
            } catch (FullCourtFacility.Refused refused) {
                // Expired, cancelled or unknown at facility: it will never commit. booking_slots still blocks the slots.
                jdbc.update("UPDATE full_court_packages SET state = 'LOCK_LOST', failure = ?, updated_at = now() WHERE id = ? AND state = 'BOOKED'",
                        trim("facility " + refused.status + " " + refused.code), packageId);
                log.error("Full-court package {} lost its facility lock ({} {}); manual review needed", packageId,
                        refused.status, refused.code);
                return "LOCK_LOST";
            } catch (FullCourtFacility.Unavailable unavailable) {
                log.warn("Facility commit attempt {} for package {} not confirmed", attempt, packageId);
            }
        }
        jdbc.update("UPDATE full_court_packages SET updated_at = now() WHERE id = ? AND state = 'BOOKED'", packageId);
        return "BOOKED";
    }

    /** Compensation. Marks FAILED only after facility confirmed the rollback; otherwise the reconciler finishes it. */
    private ResponseStatusException abort(UUID packageId, UUID reservationId, String reason, RuntimeException cause) {
        boolean released = false;
        for (int attempt = 1; attempt <= ATTEMPTS && !released; attempt++) {
            try {
                facility.rollback(reservationId);
                released = true;
            } catch (FullCourtFacility.Refused | FullCourtFacility.Unavailable error) {
                log.warn("Facility rollback attempt {} for reservation {} not confirmed", attempt, reservationId);
            }
        }
        if (released) {
            jdbc.update("UPDATE full_court_packages SET state = 'FAILED', failure = ?, updated_at = now() WHERE id = ? AND state = 'RESERVING'",
                    trim(reason), packageId);
        }
        if (cause instanceof ResponseStatusException status) {
            if (released) return status;
            return unavailable("Reservation could not be released yet; retry later");
        }
        if (cause instanceof FullCourtFacility.Refused refused) {
            if (refused.status == 409) return new ResponseStatusException(HttpStatus.CONFLICT, "Court window unavailable (" + refused.code + ")");
            if (refused.status == 404) return new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch or court not found");
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Facility rejected the reservation");
        }
        return unavailable("Full-court reservation authority unavailable");
    }

    // ---- persistence ----------------------------------------------------------------------------

    private Existing existing(long actor, UUID key) {
        return jdbc.query("SELECT id, fingerprint, state FROM full_court_packages WHERE actor_id = ? AND command_key = ?",
                (rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getString("fingerprint"), rs.getString("state")),
                actor, key).stream().findFirst().orElse(null);
    }

    /** Creates the command (or restarts a FAILED one as a new attempt); another caller winning the race is a 409. */
    private UUID store(long actor, UUID key, String fingerprint, Request request, FullCourtQuote.Priced priced,
            UUID reservationId, Existing existing) {
        var quote = priced.quote();
        String slots = slotsJson(request, priced);
        UUID id = tx.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "fullcourt:" + actor + ":" + key);
            if (existing != null) {
                var restarted = jdbc.queryForList("""
                        UPDATE full_court_packages SET state = 'RESERVING', attempt = attempt + 1, reservation_id = ?, failure = NULL,
                            court_hours = ?, subtotal = ?, discount_percent = ?, discount_amount = ?, total_amount = ?,
                            priced_slots = ?::jsonb, updated_at = now()
                        WHERE id = ? AND state = 'FAILED' RETURNING id
                        """, reservationId, quote.courtHours(), quote.subtotal(), quote.discountPercent(),
                        quote.discountAmount(), quote.total(), slots, existing.id());
                return restarted.isEmpty() ? null : existing.id();
            }
            UUID created = UUID.randomUUID();
            var inserted = jdbc.queryForList("""
                    INSERT INTO full_court_packages(id, actor_id, command_key, fingerprint, branch_id, booking_date, start_time,
                        end_time, contact_name, contact_phone, court_hours, subtotal, discount_percent, discount_amount,
                        total_amount, currency, priced_slots, reservation_id, state)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,'RESERVING')
                    ON CONFLICT (actor_id, command_key) DO NOTHING RETURNING id
                    """, created, actor, key, fingerprint, request.branchId(), request.date(), request.startTime(),
                    request.endTime(), request.contactName().trim(), request.contactPhone(), quote.courtHours(),
                    quote.subtotal(), quote.discountPercent(), quote.discountAmount(), quote.total(), quote.currency(),
                    slots, reservationId);
            return inserted.isEmpty() ? null : created;
        });
        if (id == null) throw conflict("This full-court request is already being processed; retry shortly");
        return id;
    }

    private void book(UUID packageId, UUID reservationId, long actor, Request request, FullCourtQuote.Priced priced) {
        LocalDate date = request.date();
        var courts = request.courtIds().stream().sorted().toList();
        // Same lock namespace as StaffCounter / OnlineBooking, always in ascending court order (no lock cycles).
        for (long court : courts) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "counter:" + court + ":" + date);
        }
        for (long court : courts) {
            var occupied = SlotOccupancy.active(jdbc, court, date);
            for (var slot : priced.slots().get(court)) {
                if (occupied.stream().anyMatch(o -> o.overlaps(slot.startTime(), slot.endTime()))) {
                    throw conflict("Court " + court + " already has a booking in the requested window");
                }
            }
        }
        var quote = priced.quote();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < courts.size(); index++) {
            long court = courts.get(index);
            var slots = priced.slots().get(court);
            BigDecimal subtotal = slots.stream().map(CounterDependencies.Slot::price).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            BigDecimal discount = index == courts.size() - 1 ? quote.discountAmount().subtract(allocated)
                    : subtotal.multiply(quote.discountPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
            allocated = allocated.add(discount);
            BigDecimal total = subtotal.subtract(discount);
            if (discount.signum() < 0 || total.signum() < 0 || total.compareTo(MAX) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported package amount");
            }
            String branchName = priced.venue() == null ? null : priced.venue().branchName();
            String courtName = priced.venue() == null ? null : priced.venue().courtNames().get(court);
            long bookingId = 0;
            for (int attempt = 0; attempt < 10 && bookingId == 0; attempt++) {
                String code = "FC" + String.format(Locale.ROOT, "%08d", random.nextInt(100000000));
                var created = jdbc.queryForList("""
                        INSERT INTO bookings(court_id, guest_phone, date, status, total_price, booking_code, branch_id, branch_name,
                            court_name, customer_name, payment_status)
                        VALUES (?,?,?,'CONFIRMED',?,?,?,?,?,?,'PENDING') ON CONFLICT(booking_code) DO NOTHING RETURNING id
                        """, court, request.contactPhone(), date, total, code, request.branchId(), branchName, courtName,
                        request.contactName().trim());
                if (!created.isEmpty()) bookingId = ((Number) created.getFirst().get("id")).longValue();
            }
            if (bookingId == 0) throw unavailable("Could not allocate a booking code");
            for (var slot : slots) {
                jdbc.update("INSERT INTO booking_slots(booking_id, court_id, booking_date, slot_start, slot_end, price_at_booking, source_slot_id)"
                        + " VALUES (?,?,?,?,?,?,?)", bookingId, court, date, slot.startTime(), slot.endTime(), slot.price(), slot.slotId());
            }
            jdbc.update("INSERT INTO full_court_package_bookings(package_id, court_id, booking_id) VALUES (?,?,?)",
                    packageId, court, bookingId);
        }
        int flipped = jdbc.update("UPDATE full_court_packages SET state = 'BOOKED', updated_at = now()"
                + " WHERE id = ? AND state = 'RESERVING' AND reservation_id = ?", packageId, reservationId);
        if (flipped != 1) throw conflict("The reservation attempt was superseded");
    }

    Package view(UUID packageId) {
        var row = jdbc.queryForObject("SELECT * FROM full_court_packages WHERE id = ?", (rs, n) -> {
            var bookings = jdbc.query("""
                    SELECT pb.court_id, b.id, b.booking_code, b.total_price FROM full_court_package_bookings pb
                    JOIN bookings b ON b.id = pb.booking_id WHERE pb.package_id = ? ORDER BY pb.court_id
                    """, (r, i) -> new BookingRef(r.getLong("id"), r.getLong("court_id"), r.getString("booking_code"),
                    r.getBigDecimal("total_price")), packageId);
            String state = rs.getString("state");
            String lock = switch (state) {
                case "COMMITTED" -> "COMMITTED";
                case "LOCK_LOST" -> "LOST";
                default -> "PENDING_COMMIT";
            };
            return new Package(packageId, state, lock, rs.getLong("branch_id"),
                    bookings.stream().map(BookingRef::courtId).toList(), rs.getObject("booking_date", LocalDate.class),
                    rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                    rs.getBigDecimal("court_hours"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount_percent"),
                    rs.getBigDecimal("discount_amount"), rs.getBigDecimal("total_amount"), rs.getString("currency"),
                    rs.getString("contact_name"), rs.getString("contact_phone"), bookings);
        }, packageId);
        return Objects.requireNonNull(row);
    }

    // ---- reconciliation -------------------------------------------------------------------------

    /**
     * Finishes what a crash or a facility outage interrupted: rolls back abandoned RESERVING commands (no bookings exist
     * in that state) and commits stale BOOKED ones. Idempotent; safe to run on several instances. Returns the count.
     */
    int reconcile() {
        var due = jdbc.query("""
                SELECT id, state, reservation_id FROM full_court_packages
                WHERE (state = 'RESERVING' AND updated_at < now() - make_interval(secs => ?))
                   OR (state = 'BOOKED' AND updated_at < now() - make_interval(secs => ?))
                ORDER BY updated_at LIMIT 20
                """, (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getObject("reservation_id", UUID.class)}, (double) RESERVING_STALE_SECONDS, (double) BOOKED_STALE_SECONDS);
        int done = 0;
        for (Object[] row : due) {
            UUID id = (UUID) row[0];
            UUID reservation = (UUID) row[2];
            try {
                if ("RESERVING".equals(row[1])) {
                    facility.rollback(reservation);
                    done += jdbc.update("UPDATE full_court_packages SET state = 'FAILED', failure = 'abandoned attempt rolled back', updated_at = now()"
                            + " WHERE id = ? AND state = 'RESERVING'", id);
                } else if (!"BOOKED".equals(commitOnce(id, reservation))) {
                    done++;
                }
            } catch (FullCourtFacility.Refused | FullCourtFacility.Unavailable error) {
                log.warn("Full-court reconciliation of {} deferred: {}", id, error.getMessage());
            }
        }
        return done;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private String slotsJson(Request request, FullCourtQuote.Priced priced) {
        var list = new ArrayList<Slice>();
        for (long court : request.courtIds()) {
            for (var slot : priced.slots().get(court)) {
                list.add(new Slice(court, slot.slotId(), slot.startTime(), slot.endTime(), slot.price()));
            }
        }
        try {
            return json.writeValueAsString(list);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    static String fingerprint(Request request) {
        String canonical = request.branchId() + "|" + request.courtIds().stream().sorted().toList() + "|" + request.date()
                + "|" + request.startTime() + "|" + request.endTime() + "|" + request.contactName().trim() + "|"
                + request.contactPhone();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String trim(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
