package com.fcourt.facility.service;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.api.ReservationDtos.CancelCause;
import com.fcourt.facility.api.ReservationDtos.ReservationResponse;
import com.fcourt.facility.api.ReservationDtos.ReserveRequest;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.ReservationStore;
import com.fcourt.facility.persistence.ReservationStore.Reservation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Atomic full-court reservation authority (SCRUM-241 for SCRUM-114): reserve, then commit or roll back, keyed by a
 * reservation id chosen by booking-service.
 *
 * <ul>
 *   <li>Atomic: one transaction under the branch row lock inserts the reservation and a PRIVATE_EVENT lock for every
 *       court, or nothing. Two overlapping reserves are serialised by that lock; the second sees the first's locks.
 *   <li>Idempotent: the same id with the same payload returns the same reservation; another payload is refused.
 *   <li>Leased: an uncommitted reservation ignores itself after {@code expires_at} (readers filter on it) and a sweeper
 *       cancels the rows. Commit clears the lease and is refused once it has passed.
 *   <li>Visible: locks live in {@code court_locks}, so discovery, hold, counter and online booking read them as
 *       OUT_OF_SERVICE through the existing slot-schedule without any new event.
 * </ul>
 *
 * Existing bookings and live holds are checked first through the same occupancy check as ordinary locks (fails closed).
 * That check is advisory; the final arbiter against bookings is booking-service's {@code booking_slots} unique index,
 * which the package booking writes inside the same advisory lock the counter and online paths use.
 *
 * <p>Hook for realtime fan-out (SCRUM-108): every committed change to lock state publishes a {@link CourtLocksChanged}
 * Spring event after the transaction.
 */
@Service
public class ReservationService {
    public static final String MISMATCH = "IDEMPOTENCY_MISMATCH";
    public static final String CLOSED = "RESERVATION_CLOSED";
    public static final String EXPIRED = "RESERVATION_EXPIRED";
    public static final String NOT_FOUND = "RESERVATION_NOT_FOUND";
    private static final int SWEEP_BATCH = 50;

    /** Published after commit whenever court lock state changed (reserve, commit, rollback, expiry). */
    public record CourtLocksChanged(UUID reservationId, long branchId, List<Long> courtIds, LocalDateTime startAt,
                                    LocalDateTime endAt, State state) { }

    /** {@code created} distinguishes 201 from an idempotent 200. */
    public record Result(ReservationResponse reservation, boolean created) { }

    private record Outcome(Reservation row, boolean created, String failure) { }

    private final BranchStore branches;
    private final CourtStore courts;
    private final ReservationStore reservations;
    private final LockService locks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ReservationService(BranchStore branches, CourtStore courts, ReservationStore reservations, LockService locks,
                              TransactionTemplate tx, Clock clock, ApplicationEventPublisher events) {
        this.branches = branches;
        this.courts = courts;
        this.reservations = reservations;
        this.locks = locks;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    public Result reserve(ReserveRequest request) {
        validate(request, LocalDateTime.now(clock));
        List<Long> courtIds = request.courtIds().stream().sorted().toList();
        String fingerprint = fingerprint(request, courtIds);
        BranchResponse branch = branches.findOwned(request.branchId(), request.adminId())
                .orElseThrow(BranchService::notFound);
        if (!"ACTIVE".equals(branch.status())) throw problem(HttpStatus.CONFLICT, "BRANCH_INACTIVE", "The branch is not active");
        requireCourts(branch.id(), courtIds);

        Reservation existing = reservations.find(request.reservationId()).orElse(null);
        if (existing != null) return replay(existing, fingerprint);
        // Existing bookings/holds: outside the transaction (HTTP), advisory; see class comment.
        locks.requireNoBookings(courtIds, request.startAt(), request.endAt());

        int ttl = request.ttlSeconds() == null ? com.fcourt.facility.api.ReservationDtos.DEFAULT_TTL_SECONDS
                : request.ttlSeconds();
        Outcome outcome = tx.execute(status -> {
            reservations.lockBranch(branch.id());
            Reservation raced = reservations.find(request.reservationId()).orElse(null);
            if (raced != null) return new Outcome(raced, false, null);
            requireCourts(branch.id(), courtIds);
            locks.requireNoLockOverlap(courtIds, request.startAt(), request.endAt());
            reservations.insert(request.reservationId(), branch.id(), request.adminId(), fingerprint,
                    request.startAt(), request.endAt(), ttl);
            reservations.insertLocks(request.reservationId(), branch.id(), courtIds, request.startAt(), request.endAt(),
                    request.adminId(), blankToNull(request.publicReason()));
            return new Outcome(reservations.find(request.reservationId()).orElseThrow(), true, null);
        });
        if (!outcome.created()) return replay(outcome.row(), fingerprint);
        publish(outcome.row(), courtIds);
        return new Result(response(outcome.row(), courtIds), true);
    }

    /** Idempotent: COMMITTED stays COMMITTED. Refused (409) when cancelled or when the lease already passed. */
    public ReservationResponse commit(UUID id) {
        Reservation found = reservations.find(id).orElseThrow(ReservationService::notFound);
        if (found.branchId() == null) throw problem(HttpStatus.CONFLICT, CLOSED, "The reservation was rolled back");
        Outcome outcome = tx.execute(status -> {
            reservations.lockBranch(found.branchId());
            Reservation current = reservations.find(id).orElseThrow();
            if (current.state() == State.COMMITTED) return new Outcome(current, false, null);
            if (current.state() == State.CANCELLED) return new Outcome(current, false, CLOSED);
            if (reservations.commit(id)) return new Outcome(reservations.find(id).orElseThrow(), true, null);
            reservations.expire(id);
            return new Outcome(reservations.find(id).orElseThrow(), true, EXPIRED);
        });
        List<Long> courtIds = reservations.courtIds(id);
        if (outcome.failure() != null) {
            if (outcome.created()) publish(outcome.row(), courtIds);
            throw problem(HttpStatus.CONFLICT, outcome.failure(), EXPIRED.equals(outcome.failure())
                    ? "The reservation lease expired before commit; the courts were released"
                    : "The reservation was already cancelled");
        }
        if (outcome.created()) publish(outcome.row(), courtIds);
        return response(outcome.row(), courtIds);
    }

    /**
     * Idempotent compensation: releases the locks of a RESERVED or COMMITTED reservation. An unknown id leaves a
     * tombstone so a reserve that is still in flight cannot succeed afterwards.
     */
    public ReservationResponse rollback(UUID id) {
        Reservation found = reservations.find(id).orElse(null);
        if (found == null) {
            tx.executeWithoutResult(status -> reservations.insertTombstone(id));
            found = reservations.find(id).orElseThrow();
        }
        if (found.branchId() == null) return response(found, List.of());
        long branchId = found.branchId();
        Outcome outcome = tx.execute(status -> {
            reservations.lockBranch(branchId);
            boolean cancelled = reservations.cancel(id, CancelCause.ROLLBACK);
            return new Outcome(reservations.find(id).orElseThrow(), cancelled, null);
        });
        List<Long> courtIds = reservations.courtIds(id);
        if (outcome.created()) publish(outcome.row(), courtIds);
        return response(outcome.row(), courtIds);
    }

    public ReservationResponse get(UUID id) {
        Reservation found = reservations.find(id).orElseThrow(ReservationService::notFound);
        return response(found, reservations.courtIds(id));
    }

    /** Cancels uncommitted reservations past their lease. Returns how many were released. Safe to run concurrently. */
    public int expireDue() {
        int released = 0;
        for (var due : reservations.expired(SWEEP_BATCH)) {
            Outcome outcome = tx.execute(status -> {
                reservations.lockBranch(due.branchId());
                boolean expired = reservations.expire(due.id());
                return new Outcome(reservations.find(due.id()).orElseThrow(), expired, null);
            });
            if (outcome.created()) {
                released++;
                publish(outcome.row(), reservations.courtIds(due.id()));
            }
        }
        return released;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Result replay(Reservation existing, String fingerprint) {
        if (existing.fingerprint() == null) {
            throw problem(HttpStatus.CONFLICT, CLOSED, "The reservation id was rolled back before it was created");
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw problem(HttpStatus.CONFLICT, MISMATCH, "The reservation id was already used for a different request");
        }
        if (existing.leaseExpired()) {
            Long branchId = existing.branchId();
            Outcome outcome = tx.execute(status -> {
                reservations.lockBranch(branchId);
                boolean cancelled = reservations.expire(existing.id());
                return new Outcome(reservations.find(existing.id()).orElseThrow(), cancelled, null);
            });
            if (outcome.created()) publish(outcome.row(), reservations.courtIds(existing.id()));
            throw problem(HttpStatus.CONFLICT, EXPIRED, "The reservation lease expired; reserve again with a new id");
        }
        if (existing.state() == State.CANCELLED) {
            throw problem(HttpStatus.CONFLICT, CLOSED, "The reservation was cancelled; reserve again with a new id");
        }
        return new Result(response(existing, reservations.courtIds(existing.id())), false);
    }

    private void requireCourts(long branchId, List<Long> courtIds) {
        Map<Long, CourtResponse> live = new java.util.HashMap<>();
        for (CourtResponse court : courts.listByBranch(branchId)) live.put(court.id(), court);
        for (long courtId : courtIds) {
            CourtResponse court = live.get(courtId);
            if (court == null) throw CourtService.notFound();
            if (!"ACTIVE".equals(court.status())) {
                throw problem(HttpStatus.CONFLICT, "COURT_INACTIVE", "Court " + courtId + " is not active");
            }
        }
    }

    private void publish(Reservation row, List<Long> courtIds) {
        if (row.branchId() == null) return;
        events.publishEvent(new CourtLocksChanged(row.id(), row.branchId(), courtIds, row.startAt(), row.endAt(),
                row.state()));
    }

    private static ReservationResponse response(Reservation row, List<Long> courtIds) {
        return new ReservationResponse(row.id(), row.branchId(), row.state(), courtIds, row.startAt(), row.endAt(),
                row.expiresAt(), row.committedAt(), row.cancelledAt(), row.cancelCause());
    }

    static void validate(ReserveRequest request, LocalDateTime now) {
        if (new HashSet<>(request.courtIds()).size() != request.courtIds().size()) {
            throw badRequest("courtIds must not contain duplicates");
        }
        if (!onBoundary(request.startAt()) || !onBoundary(request.endAt())) {
            throw badRequest("startAt and endAt must be on a 30-minute boundary (minutes 00 or 30)");
        }
        if (!request.endAt().isAfter(request.startAt())) throw badRequest("endAt must be after startAt");
        if (!request.endAt().isAfter(now)) throw badRequest("The reservation window is already in the past");
        if (Duration.between(request.startAt(), request.endAt()).compareTo(LockService.MAX_WINDOW) > 0) {
            throw badRequest("A reservation window can span at most " + LockService.MAX_WINDOW.toDays() + " days");
        }
    }

    private static boolean onBoundary(LocalDateTime value) {
        return value.getMinute() % LockService.SLOT_MINUTES == 0 && value.getSecond() == 0 && value.getNano() == 0;
    }

    /** Stable digest of everything that defines what is held; ttl and public text are deliberately excluded. */
    static String fingerprint(ReserveRequest request, List<Long> sortedCourtIds) {
        String canonical = request.branchId() + "|" + request.adminId() + "|" + sortedCourtIds + "|"
                + request.startAt() + "|" + request.endAt();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ErrorResponseException notFound() {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Reservation not found");
    }

    static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
