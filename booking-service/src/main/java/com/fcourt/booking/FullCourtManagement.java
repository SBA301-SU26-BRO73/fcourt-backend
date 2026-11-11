package com.fcourt.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lifecycle of an existing full-court package after the reservation saga (SCRUM-242): read/list (including the LOCK_LOST
 * alert queue), whole-package cancel and the relock repair. Authority is the branch owner, checked against facility's
 * current branch list, never only the creator of the command.
 *
 * <p>Cancel is a saga too: (1) one transaction cancels every member booking with the same rules and outbox event as a
 * customer cancellation (cutoff, refund percent) or none at all, and moves the package to CANCELLING; (2) facility
 * {@code rollback} releases the lock (idempotent, works on a committed reservation); (3) CANCELLED. Step 2 failing leaves
 * CANCELLING, retried by the same call or by {@link #reconcile()}. Relock re-reserves the same window with a new
 * reservation id and {@code relock=true} (the occupying bookings are this package's own) and commits it.
 */
@Service
class FullCourtManagement {
    private static final Logger log = LoggerFactory.getLogger(FullCourtManagement.class);
    static final Set<String> STATES = Set.of("RESERVING", "BOOKED", "COMMITTED", "FAILED", "LOCK_LOST", "CANCELLING", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FullCourtReservation reservations;
    private final FullCourtFacility facility;
    private final BookingCancellation cancellation;
    private final AdminBranchAuthority branches;

    FullCourtManagement(JdbcTemplate jdbc, TransactionTemplate tx, FullCourtReservation reservations,
            FullCourtFacility facility, BookingCancellation cancellation, AdminBranchAuthority branches) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.reservations = reservations;
        this.facility = facility;
        this.cancellation = cancellation;
        this.branches = branches;
    }

    FullCourtReservation.Package get(Jwt jwt, UUID id) {
        requireBranch(jwt, id);
        return reservations.view(id);
    }

    /** Packages of the caller's branches, newest first; {@code state=LOCK_LOST} is the repair queue. */
    List<FullCourtReservation.Package> list(Jwt jwt, String state, int page, int size) {
        FullCourtQuote.requireAdmin(jwt);
        if (state != null && !STATES.contains(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown package state");
        }
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        var owned = branches.ownedBranches(jwt);
        if (owned.isEmpty()) return List.of();
        Long[] ownedIds = owned.toArray(new Long[0]);
        var ids = state == null
                ? jdbc.queryForList("SELECT id FROM full_court_packages WHERE branch_id = ANY(?)"
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", UUID.class, ownedIds, size, (long) page * size)
                : jdbc.queryForList("SELECT id FROM full_court_packages WHERE branch_id = ANY(?) AND state = ?"
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", UUID.class, ownedIds, state, size, (long) page * size);
        return ids.stream().map(reservations::view).toList();
    }

    FullCourtReservation.Result cancel(Jwt jwt, UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason required (1-500 characters)");
        }
        requireBranch(jwt, id);
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT state FROM full_court_packages WHERE id = ? FOR UPDATE", id);
            if (rows.isEmpty()) throw missing();
            switch ((String) rows.getFirst().get("state")) {
                case "CANCELLED", "CANCELLING" -> { /* already cancelled: only the lock release may still be pending */ }
                case "COMMITTED", "BOOKED", "LOCK_LOST" -> cancelMembers(id, reason.strip());
                default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "The package has no bookings to cancel yet");
            }
        });
        return new FullCourtReservation.Result(releaseLock(id) ? 200 : 202, reservations.view(id));
    }

    private void cancelMembers(UUID id, String reason) {
        var members = jdbc.queryForList("""
                SELECT b.* FROM bookings b JOIN full_court_package_bookings pb ON pb.booking_id = b.id
                WHERE pb.package_id = ? ORDER BY b.id FOR UPDATE OF b
                """, id);
        var policies = new ArrayList<BookingCancellation.Policy>();
        for (Map<String, Object> member : members) {
            if ("CANCELLED".equals(member.get("status"))) {
                policies.add(null);
                continue;
            }
            var policy = cancellation.policyFor(member);
            if (!policy.allowed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, policy.reason());
            policies.add(policy);
        }
        for (int index = 0; index < members.size(); index++) {
            if (policies.get(index) != null) cancellation.applyCancellation(members.get(index), reason, policies.get(index));
        }
        jdbc.update("UPDATE full_court_packages SET state = 'CANCELLING', cancel_reason = ?, cancelled_at = now(), updated_at = now()"
                + " WHERE id = ?", reason, id);
    }

    /** Step 2 of cancel: true when the facility lock is released (package CANCELLED), false while still pending. */
    private boolean releaseLock(UUID id) {
        var row = jdbc.queryForMap("SELECT state, reservation_id FROM full_court_packages WHERE id = ?", id);
        if ("CANCELLED".equals(row.get("state"))) return true;
        try {
            facility.rollback((UUID) row.get("reservation_id"));
        } catch (FullCourtFacility.Refused | FullCourtFacility.Unavailable error) {
            log.warn("Facility lock release for cancelled package {} deferred: {}", id, error.getMessage());
            return false;
        }
        jdbc.update("UPDATE full_court_packages SET state = 'CANCELLED', updated_at = now() WHERE id = ? AND state = 'CANCELLING'", id);
        return true;
    }

    /** Repair: bookings stand but facility lost the lease. Re-reserves with a new id (relock) and commits it. */
    FullCourtReservation.Result relock(Jwt jwt, UUID id) {
        requireBranch(jwt, id);
        var row = jdbc.queryForMap("SELECT state, branch_id, booking_date, start_time, end_time FROM full_court_packages WHERE id = ?", id);
        String state = (String) row.get("state");
        if ("COMMITTED".equals(state)) return new FullCourtReservation.Result(200, reservations.view(id));
        if ("BOOKED".equals(state)) return reservations.finish(id, false);
        if (!"LOCK_LOST".equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a package with a lost lock can be relocked");
        }
        long actor = BookingQueries.actor(jwt);
        UUID fresh = UUID.randomUUID();
        int claimed = jdbc.update("UPDATE full_court_packages SET state = 'BOOKED', reservation_id = ?, attempt = attempt + 1,"
                + " failure = NULL, updated_at = now() WHERE id = ? AND state = 'LOCK_LOST'", fresh, id);
        if (claimed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The package changed; retry");
        var courts = jdbc.queryForList("SELECT pb.court_id FROM full_court_package_bookings pb JOIN bookings b ON b.id = pb.booking_id"
                + " WHERE pb.package_id = ? AND b.status <> 'CANCELLED' ORDER BY pb.court_id", Long.class, id);
        var date = ((java.sql.Date) row.get("booking_date")).toLocalDate();
        var start = ((java.sql.Time) row.get("start_time")).toLocalTime();
        var end = ((java.sql.Time) row.get("end_time")).toLocalTime();
        try {
            facility.reserve(new FullCourtFacility.Reserve(fresh, ((Number) row.get("branch_id")).longValue(), actor, courts,
                    date.atTime(start).toString(), date.atTime(end).toString(), FullCourtReservation.TTL_SECONDS, null, true));
        } catch (FullCourtFacility.Refused | FullCourtFacility.Unavailable error) {
            try {
                facility.rollback(fresh);
            } catch (FullCourtFacility.Refused | FullCourtFacility.Unavailable ignored) {
                // facility never confirmed it; the id is burnt either way
            }
            jdbc.update("UPDATE full_court_packages SET state = 'LOCK_LOST', failure = 'relock failed', updated_at = now()"
                    + " WHERE id = ? AND state = 'BOOKED' AND reservation_id = ?", id, fresh);
            if (error instanceof FullCourtFacility.Refused refused && refused.status == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Relock refused (" + refused.code + ")");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Full-court reservation authority unavailable");
        }
        return reservations.finish(id, false);
    }

    /** Retries lock releases of cancelled packages whose facility rollback was not confirmed. */
    int reconcile() {
        var due = jdbc.queryForList("SELECT id FROM full_court_packages WHERE state = 'CANCELLING'"
                + " AND updated_at < now() - make_interval(secs => ?) ORDER BY updated_at LIMIT 20", UUID.class,
                (double) FullCourtReservation.BOOKED_STALE_SECONDS);
        int done = 0;
        for (UUID id : due) {
            if (releaseLock(id)) done++;
            else jdbc.update("UPDATE full_court_packages SET updated_at = now() WHERE id = ? AND state = 'CANCELLING'", id);
        }
        return done;
    }

    private long requireBranch(Jwt jwt, UUID id) {
        FullCourtQuote.requireAdmin(jwt);
        var rows = jdbc.queryForList("SELECT branch_id FROM full_court_packages WHERE id = ?", Long.class, id);
        if (rows.isEmpty()) throw missing();
        if (!branches.ownedBranches(jwt).contains(rows.getFirst())) throw missing();
        return rows.getFirst();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found");
    }
}
