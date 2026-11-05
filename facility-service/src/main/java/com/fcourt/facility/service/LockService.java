package com.fcourt.facility.service;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.api.LockDtos.BranchLockRequest;
import com.fcourt.facility.api.LockDtos.LockDetails;
import com.fcourt.facility.api.LockDtos.LockGroupResponse;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockSlotsRequest;
import com.fcourt.facility.api.LockDtos.LockType;
import com.fcourt.facility.api.LockDtos.SlotConflict;
import com.fcourt.facility.integration.SlotAvailabilityClient;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.LockStore;
import com.fcourt.facility.persistence.LockStore.NewLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slot locks, out-of-service windows and private-event full-court locks (SCRUM-49 / SCRUM-111).
 *
 * <p>A lock is a venue-local window [startAt, endAt) on one court; a branch-wide request creates one
 * lock per court under a shared group id, all in one transaction. Discovery, slot-hold-engine and the
 * counter booking path already read {@code slot-schedule.outOfService}, so a lock takes effect for
 * every consumer without a new event. Existing bookings and live holds are never overridden: the
 * request fails with 409 {@code BOOKING_CONFLICT} listing the colliding slots.
 */
@Service
public class LockService {
    public static final int SLOT_MINUTES = 30;
    public static final Duration MAX_WINDOW = Duration.ofDays(31);
    private static final Set<String> BLOCKING_STATUSES = Set.of("BOOKED", "HOLD");

    private final BranchStore branches;
    private final CourtStore courts;
    private final LockStore locks;
    private final CourtService courtService;
    private final SlotAvailabilityClient availability;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LockService(BranchStore branches, CourtStore courts, LockStore locks, CourtService courtService,
                       SlotAvailabilityClient availability, TransactionTemplate tx, Clock clock) {
        this.branches = branches;
        this.courts = courts;
        this.locks = locks;
        this.courtService = courtService;
        this.availability = availability;
        this.tx = tx;
        this.clock = clock;
    }

    /** POST /api/admin/courts/lock-slots: lock one court for a window. */
    public LockResponse lockCourt(long adminId, LockSlotsRequest request) {
        validate(request, LocalDateTime.now(clock));
        CourtResponse court = courtService.owned(adminId, request.courtId()).court();
        requireNoBookings(List.of(court.id()), request);
        UUID group = UUID.randomUUID();
        return tx.execute(status -> {
            lockRow(court.branchId(), adminId);
            courtService.owned(adminId, court.id());
            requireNoLockOverlap(List.of(court.id()), request);
            return locks.insert(newLock(group, court.branchId(), court.id(), request, adminId));
        });
    }

    /** POST /api/admin/courts/out-of-service: lock every court (or a chosen list) of a branch, atomically. */
    public LockGroupResponse lockBranch(long adminId, BranchLockRequest request) {
        validate(request, LocalDateTime.now(clock));
        BranchResponse branch = branches.findOwned(request.branchId(), adminId).orElseThrow(BranchService::notFound);
        List<Long> courtIds = selectCourts(branch.id(), request);
        requireNoBookings(courtIds, request);
        UUID group = UUID.randomUUID();
        List<LockResponse> created = tx.execute(status -> {
            lockRow(branch.id(), adminId);
            // Re-read under the row lock: a court deleted meanwhile must not receive a lock.
            List<Long> live = selectCourts(branch.id(), request);
            requireNoLockOverlap(live, request);
            List<LockResponse> rows = new ArrayList<>(live.size());
            for (long courtId : live) rows.add(locks.insert(newLock(group, branch.id(), courtId, request, adminId)));
            return rows;
        });
        return new LockGroupResponse(group, branch.id(), created.size(), created);
    }

    public List<LockResponse> list(long adminId, long branchId, Long courtId, LocalDateTime from, LocalDateTime to) {
        branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
        if (from != null && to != null && !to.isAfter(from)) throw badRequest("to must be after from");
        if (courtId != null && courts.listByBranch(branchId).stream().noneMatch(court -> court.id() == courtId)) {
            throw CourtService.notFound();
        }
        return locks.listByBranch(branchId, courtId, from, to);
    }

    /** DELETE /api/admin/courts/lock-slots/{id}: unlock one court window. */
    public void unlock(long adminId, long lockId) {
        long[] owner = locks.findOwner(lockId).filter(row -> row[2] == adminId).orElseThrow(LockService::lockNotFound);
        tx.executeWithoutResult(status -> {
            lockRow(owner[1], adminId);
            if (locks.cancel(lockId, adminId) == 0) throw lockNotFound();
        });
    }

    /** DELETE /api/admin/courts/out-of-service/{groupId}: unlock every active court of one branch-wide request. */
    public void unlockGroup(long adminId, UUID groupId) {
        long[] owner = locks.findGroupOwner(groupId).filter(row -> row[1] == adminId).orElseThrow(LockService::lockNotFound);
        tx.executeWithoutResult(status -> {
            lockRow(owner[0], adminId);
            if (locks.cancelGroup(groupId, adminId) == 0) throw lockNotFound();
        });
    }

    // ---- validation -----------------------------------------------------------------------------

    static void validate(LockDetails request, LocalDateTime now) {
        LocalDateTime start = request.startAt();
        LocalDateTime end = request.endAt();
        if (!onSlotBoundary(start) || !onSlotBoundary(end)) {
            throw badRequest("startAt and endAt must be on a 30-minute boundary (minutes 00 or 30)");
        }
        if (!end.isAfter(start)) throw badRequest("endAt must be after startAt");
        if (!end.isAfter(now)) throw badRequest("The lock window is already in the past");
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw badRequest("A lock window can span at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    private static boolean onSlotBoundary(LocalDateTime value) {
        return value.getMinute() % SLOT_MINUTES == 0 && value.getSecond() == 0 && value.getNano() == 0;
    }

    private List<Long> selectCourts(long branchId, BranchLockRequest request) {
        List<Long> live = courts.listByBranch(branchId).stream().map(CourtResponse::id).toList();
        if (Boolean.TRUE.equals(request.allCourts())) {
            if (live.isEmpty()) throw badRequest("The branch has no courts to lock");
            return live;
        }
        if (!live.containsAll(request.courtIds())) throw CourtService.notFound();
        return request.courtIds();
    }

    // ---- conflicts ------------------------------------------------------------------------------

    /** Refuses the lock when a booking or a live hold sits inside the window (checked before the DB transaction). */
    private void requireNoBookings(List<Long> courtIds, LockDetails request) {
        List<SlotConflict> conflicts = new ArrayList<>();
        for (long courtId : courtIds) {
            for (var date : SlotWindows.dates(request.startAt(), request.endAt())) {
                for (var slot : availability.slots(courtId, date)) {
                    if (BLOCKING_STATUSES.contains(slot.status())
                            && SlotWindows.overlaps(date, slot.startTime(), slot.endTime(), request.startAt(), request.endAt())) {
                        conflicts.add(new SlotConflict(courtId, date, slot.slotId(), slot.startTime(),
                                slot.endTime(), slot.status()));
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            long booked = conflicts.stream().filter(conflict -> "BOOKED".equals(conflict.status())).count();
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Cannot lock: " + booked + " slot(s) already booked and " + (conflicts.size() - booked)
                            + " held by customers. Contact the customers or choose another window; nothing was locked.");
            problem.setProperty("code", "BOOKING_CONFLICT");
            problem.setProperty("conflicts", conflicts);
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }
    }

    private void requireNoLockOverlap(List<Long> courtIds, LockDetails request) {
        List<LockResponse> overlapping = courtIds.stream()
                .flatMap(courtId -> locks.overlapping(courtId, request.startAt(), request.endAt()).stream()).toList();
        if (!overlapping.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The window overlaps an existing lock on court(s) " + overlapping.stream()
                            .map(lock -> Long.toString(lock.courtId())).distinct().collect(Collectors.joining(", "))
                            + "; cancel it first");
            problem.setProperty("code", "LOCK_OVERLAP");
            problem.setProperty("lockIds", overlapping.stream().map(LockResponse::id).toList());
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void lockRow(long branchId, long adminId) {
        if (!branches.lockOwned(branchId, adminId)) throw BranchService.notFound();
    }

    private static NewLock newLock(UUID group, long branchId, long courtId, LockDetails request, long adminId) {
        LockType type = request.type() == null ? LockType.OUT_OF_SERVICE : request.type();
        return new NewLock(group, branchId, courtId, type, request.startAt(), request.endAt(),
                request.reason().trim(), blankToNull(request.notes()), blankToNull(request.publicReason()),
                blankToNull(request.contactName()), blankToNull(request.contactPhone()), adminId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException lockNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Lock not found");
    }
}
