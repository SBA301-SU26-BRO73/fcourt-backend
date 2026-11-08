package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.api.LockDtos.BranchLockRequest;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockSlotsRequest;
import com.fcourt.facility.api.LockDtos.LockType;
import com.fcourt.facility.integration.SlotAvailabilityClient;
import com.fcourt.facility.integration.SlotAvailabilityClient.SlotConflict;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import com.fcourt.facility.persistence.LockStore;
import com.fcourt.facility.persistence.LockStore.NewLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

class LockServiceTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 8, 0);
    static final LocalDate DAY = LocalDate.of(2030, 1, 5);
    static final long ADMIN = 7;

    BranchStore branches = mock(BranchStore.class);
    CourtStore courts = mock(CourtStore.class);
    LockStore locks = mock(LockStore.class);
    CourtService courtService = mock(CourtService.class);
    SlotAvailabilityClient availability = mock(SlotAvailabilityClient.class);
    LockService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new LockService(branches, courts, locks, courtService, availability,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);
        when(branches.lockOwned(anyLong(), anyLong())).thenReturn(true);
        when(locks.overlapping(anyLong(), any(), any())).thenReturn(List.of());
        when(locks.insert(any())).thenAnswer(call -> {
            NewLock lock = call.getArgument(0);
            return new LockResponse(1, lock.groupId(), lock.branchId(), lock.courtId(), lock.type(), lock.startAt(),
                    lock.endAt(), lock.reason(), lock.notes(), lock.publicReason(), lock.contactName(),
                    lock.contactPhone(), lock.createdBy(), OffsetDateTime.now());
        });
    }

    static CourtResponse court(long id) {
        return new CourtResponse(id, 3, "Court " + id, 1, null, null, null, "ACTIVE", Instant.now(), Instant.now());
    }

    void ownsCourt(long id) {
        when(courtService.owned(ADMIN, id)).thenReturn(new OwnedCourt(court(id), ADMIN, "ACTIVE"));
    }

    static LockSlotsRequest request(long courtId, LocalDateTime from, LocalDateTime to) {
        return new LockSlotsRequest(courtId, from, to, null, "Maintenance", null, null, null, null);
    }

    static SlotConflict slot(long id, String start, String end, String status) {
        return new SlotConflict(id, LocalTime.parse(start), LocalTime.parse(end), status);
    }

    static ProblemDetail problem(Throwable error) {
        assertThat(error).isInstanceOf(ErrorResponseException.class);
        return ((ErrorResponseException) error).getBody();
    }

    // ---- window validation --------------------------------------------------------------------

    @Test
    void rejectsWindowsNotOnThirtyMinuteBoundaries() {
        assertThatThrownBy(() -> LockService.validate(request(1, DAY.atTime(13, 15), DAY.atTime(18, 0)), NOW))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("30-minute");
        assertThatThrownBy(() -> LockService.validate(request(1, DAY.atTime(13, 0), DAY.atTime(18, 0, 5)), NOW))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsEmptyReversedPastAndOverlongWindows() {
        assertThatThrownBy(() -> LockService.validate(request(1, DAY.atTime(13, 0), DAY.atTime(13, 0)), NOW))
                .hasMessageContaining("after startAt");
        assertThatThrownBy(() -> LockService.validate(request(1, DAY.atTime(14, 0), DAY.atTime(13, 0)), NOW))
                .hasMessageContaining("after startAt");
        assertThatThrownBy(() -> LockService.validate(request(1, NOW.minusDays(2), NOW.minusDays(1)), NOW))
                .hasMessageContaining("past");
        assertThatThrownBy(() -> LockService.validate(request(1, DAY.atTime(0, 0), DAY.plusDays(32).atTime(0, 0)), NOW))
                .hasMessageContaining("31 days");
    }

    @Test
    void acceptsWindowCrossingMidnightMultiDayAndAlreadyStarted() {
        LockService.validate(request(1, DAY.atTime(22, 0), DAY.plusDays(1).atTime(2, 0)), NOW);
        LockService.validate(request(1, DAY.atTime(0, 0), DAY.plusDays(31).atTime(0, 0)), NOW);
        LockService.validate(request(1, NOW.minusHours(2).withMinute(0), NOW.plusHours(2).withMinute(0)), NOW);
    }

    // ---- booking conflicts --------------------------------------------------------------------

    @Test
    void refusesToLockBookedAndHeldSlotsAndWritesNothing() {
        ownsCourt(5);
        when(availability.conflicts(5, DAY)).thenReturn(List.of(
                slot(1, "12:30", "13:00", "BOOKED"),   // before the window
                slot(2, "13:00", "13:30", "BOOKED"),
                slot(3, "13:30", "14:00", "HOLD"),
                slot(5, "18:00", "18:30", "BOOKED"))); // after the window

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(5, DAY.atTime(13, 0), DAY.atTime(18, 0))))
                .satisfies(error -> {
                    ProblemDetail body = problem(error);
                    assertThat(body.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(body.getProperties()).containsEntry("code", "BOOKING_CONFLICT");
                    assertThat(body.getProperties().get("conflicts").toString())
                            .contains("slotId=2").contains("slotId=3").doesNotContain("slotId=1")
                            .doesNotContain("slotId=5");
                    assertThat(body.getDetail()).contains("1 slot(s) already booked").contains("1 held");
                });
        verify(locks, never()).insert(any());
    }

    @Test
    void checksEveryDateOfAMultiDayWindow() {
        ownsCourt(5);
        when(availability.conflicts(5, DAY)).thenReturn(List.of());
        when(availability.conflicts(5, DAY.plusDays(1))).thenReturn(List.of(slot(9, "00:00", "00:30", "BOOKED")));

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(5, DAY.atTime(22, 0), DAY.plusDays(1).atTime(2, 0))))
                .satisfies(error -> assertThat(problem(error).getProperties()).containsEntry("code", "BOOKING_CONFLICT"));
        verify(availability).conflicts(5, DAY);
        verify(availability).conflicts(5, DAY.plusDays(1));
        verify(locks, never()).insert(any());
    }

    @Test
    void failsClosedWhenBookingsCannotBeVerified() {
        ownsCourt(5);
        when(availability.conflicts(anyLong(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(5, DAY.atTime(13, 0), DAY.atTime(18, 0))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(503)));
        verify(locks, never()).insert(any());
    }

    @Test
    void locksFreeWindowAndDefaultsToOutOfService() {
        ownsCourt(5);
        when(availability.conflicts(5, DAY)).thenReturn(List.of());

        LockResponse created = service.lockCourt(ADMIN, new LockSlotsRequest(5L, DAY.atTime(13, 0), DAY.atTime(18, 0),
                null, "  Floor repair ", "internal", " ", null, null));

        ArgumentCaptor<NewLock> saved = ArgumentCaptor.forClass(NewLock.class);
        verify(locks).insert(saved.capture());
        assertThat(created.type()).isEqualTo(LockType.OUT_OF_SERVICE);
        assertThat(saved.getValue().reason()).isEqualTo("Floor repair");
        assertThat(saved.getValue().publicReason()).isNull();
        assertThat(saved.getValue().createdBy()).isEqualTo(ADMIN);
    }

    @Test
    void refusesOverlapWithAnExistingLock() {
        ownsCourt(5);
        when(availability.conflicts(5, DAY)).thenReturn(List.of());
        var existing = new LockResponse(40, UUID.randomUUID(), 3, 5, LockType.OUT_OF_SERVICE, DAY.atTime(15, 0),
                DAY.atTime(16, 0), "x", null, null, null, null, ADMIN, OffsetDateTime.now());
        when(locks.overlapping(5, DAY.atTime(13, 0), DAY.atTime(18, 0))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(5, DAY.atTime(13, 0), DAY.atTime(18, 0))))
                .satisfies(error -> {
                    assertThat(problem(error).getProperties()).containsEntry("code", "LOCK_OVERLAP");
                    assertThat(problem(error).getProperties()).containsEntry("lockIds", List.of(40L));
                });
        verify(locks, never()).insert(any());
    }

    // ---- branch-wide ---------------------------------------------------------------------------

    BranchResponse branch() {
        return new BranchResponse(3, ADMIN, "B", "a", null, null, "c", null, null, null, LocalTime.of(6, 0),
                LocalTime.of(23, 0), 30, "bank", "970436", "123456", "owner", "ACTIVE", 3, Instant.now(), Instant.now());
    }

    @Test
    void branchWideLockCreatesOneLockPerCourtUnderOneGroup() {
        when(branches.findOwned(3, ADMIN)).thenReturn(Optional.of(branch()));
        when(courts.listByBranch(3)).thenReturn(List.of(court(10), court(11), court(12)));
        when(availability.conflicts(anyLong(), any())).thenReturn(List.of());

        var request = new BranchLockRequest(3L, true, null, DAY.atTime(13, 0), DAY.atTime(18, 0),
                LockType.PRIVATE_EVENT, "Tournament", null, "Giai dau", "Ban to chuc", "0901234567");
        var response = service.lockBranch(ADMIN, request);

        assertThat(response.totalLocks()).isEqualTo(3);
        assertThat(response.locks()).extracting(LockResponse::courtId).containsExactly(10L, 11L, 12L);
        assertThat(response.locks()).allSatisfy(lock -> {
            assertThat(lock.groupId()).isEqualTo(response.groupId());
            assertThat(lock.type()).isEqualTo(LockType.PRIVATE_EVENT);
        });
    }

    @Test
    void branchWideLockIsAllOrNothingWhenOneCourtHasABooking() {
        when(branches.findOwned(3, ADMIN)).thenReturn(Optional.of(branch()));
        when(courts.listByBranch(3)).thenReturn(List.of(court(10), court(11)));
        when(availability.conflicts(10, DAY)).thenReturn(List.of());
        when(availability.conflicts(11, DAY)).thenReturn(List.of(slot(2, "13:00", "13:30", "BOOKED")));

        var request = new BranchLockRequest(3L, true, null, DAY.atTime(13, 0), DAY.atTime(18, 0),
                null, "Tournament", null, null, null, null);
        assertThatThrownBy(() -> service.lockBranch(ADMIN, request))
                .satisfies(error -> assertThat(problem(error).getProperties()).containsEntry("code", "BOOKING_CONFLICT"));
        verify(locks, never()).insert(any());
    }

    @Test
    void branchWideLockRejectsCourtsOutsideTheBranchAndUnknownBranch() {
        when(branches.findOwned(3, ADMIN)).thenReturn(Optional.of(branch()));
        when(courts.listByBranch(3)).thenReturn(List.of(court(10)));
        var foreign = new BranchLockRequest(3L, false, List.of(10L, 99L), DAY.atTime(13, 0), DAY.atTime(18, 0),
                null, "x", null, null, null, null);
        assertThatThrownBy(() -> service.lockBranch(ADMIN, foreign))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));

        when(branches.findOwned(4, ADMIN)).thenReturn(Optional.empty());
        var unknown = new BranchLockRequest(4L, true, null, DAY.atTime(13, 0), DAY.atTime(18, 0),
                null, "x", null, null, null, null);
        assertThatThrownBy(() -> service.lockBranch(ADMIN, unknown))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
        verify(locks, never()).insert(any());
    }

    @Test
    void unlockOfAnotherOwnersLockIsNotFound() {
        when(locks.findOwner(40)).thenReturn(Optional.of(new long[] {5, 3, 999}));
        assertThatThrownBy(() -> service.unlock(ADMIN, 40))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404)));
        verify(locks, never()).cancel(anyLong(), anyLong());
    }
}
