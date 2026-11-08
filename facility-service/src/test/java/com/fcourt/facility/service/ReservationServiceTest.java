package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.api.ReservationDtos.CancelCause;
import com.fcourt.facility.api.ReservationDtos.ReserveRequest;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.ReservationStore;
import com.fcourt.facility.persistence.ReservationStore.Expired;
import com.fcourt.facility.persistence.ReservationStore.Reservation;
import com.fcourt.facility.service.ReservationService.CourtLocksChanged;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

class ReservationServiceTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 8, 0);
    static final LocalDateTime START = LocalDateTime.of(2030, 1, 5, 8, 0);
    static final LocalDateTime END = LocalDateTime.of(2030, 1, 5, 10, 0);
    static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final long ADMIN = 7;
    static final long BRANCH = 3;

    BranchStore branches = mock(BranchStore.class);
    CourtStore courts = mock(CourtStore.class);
    ReservationStore store = mock(ReservationStore.class);
    LockService locks = mock(LockService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    ReservationService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new ReservationService(branches, courts, store, locks,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), clock, events);
        when(branches.findOwned(BRANCH, ADMIN)).thenReturn(Optional.of(branch("ACTIVE")));
        when(courts.listByBranch(BRANCH)).thenReturn(List.of(court(1, "ACTIVE"), court(2, "ACTIVE")));
    }

    static BranchResponse branch(String status) {
        return new BranchResponse(BRANCH, ADMIN, "Venue", "1 Street", null, null, "HCM", null, null, null,
                java.time.LocalTime.of(6, 0), java.time.LocalTime.of(22, 0), 30, null, null, null, null, status, 2,
                null, null, List.of());
    }

    static CourtResponse court(long id, String status) {
        return new CourtResponse(id, BRANCH, "Court " + id, 1, null, null, java.math.BigDecimal.TEN, status, null, null);
    }

    static ReserveRequest request(List<Long> courtIds) {
        return new ReserveRequest(ID, BRANCH, ADMIN, courtIds, START, END, null, " Giai dau ");
    }

    static String fingerprint(List<Long> sortedCourts) {
        return ReservationService.fingerprint(request(sortedCourts), sortedCourts);
    }

    static Reservation row(State state, String fingerprint, boolean leaseExpired) {
        return new Reservation(ID, BRANCH, ADMIN, fingerprint, state, START, END,
                state == State.COMMITTED ? null : OffsetDateTime.now().plusMinutes(2),
                state == State.COMMITTED ? OffsetDateTime.now() : null, null,
                state == State.CANCELLED ? CancelCause.ROLLBACK : null, leaseExpired);
    }

    static int status(Throwable error) {
        HttpStatusCode code = error instanceof ErrorResponseException e ? e.getStatusCode()
                : ((ResponseStatusException) error).getStatusCode();
        return code.value();
    }

    static String code(Throwable error) {
        return (String) ((ErrorResponseException) error).getBody().getProperties().get("code");
    }

    @Test
    void reserveInsertsReservationAndEveryCourtLockAndPublishesTheChange() {
        when(store.find(ID)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(row(State.RESERVED, "x", false)));
        when(store.courtIds(ID)).thenReturn(List.of(1L, 2L));

        var result = service.reserve(request(List.of(2L, 1L)));

        assertThat(result.created()).isTrue();
        assertThat(result.reservation().state()).isEqualTo(State.RESERVED);
        verify(locks).requireNoBookings(List.of(1L, 2L), START, END);
        verify(locks).requireNoLockOverlap(List.of(1L, 2L), START, END);
        verify(store).lockBranch(BRANCH);
        verify(store).insert(eq(ID), eq(BRANCH), eq(ADMIN), eq(fingerprint(List.of(1L, 2L))), eq(START), eq(END), eq(120));
        verify(store).insertLocks(ID, BRANCH, List.of(1L, 2L), START, END, ADMIN, "Giai dau");
        var event = ArgumentCaptor.forClass(CourtLocksChanged.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().courtIds()).containsExactly(1L, 2L);
        assertThat(event.getValue().state()).isEqualTo(State.RESERVED);
    }

    @Test
    void sameIdAndPayloadIsAnIdempotentReplayWithoutNewLocksOrOccupancyChecks() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, fingerprint(List.of(1L, 2L)), false)));
        when(store.courtIds(ID)).thenReturn(List.of(1L, 2L));

        var result = service.reserve(request(List.of(1L, 2L)));

        assertThat(result.created()).isFalse();
        assertThat(result.reservation().courtIds()).containsExactly(1L, 2L);
        verify(store, never()).insert(any(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
        verify(locks, never()).requireNoBookings(anyList(), any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void courtOrderDoesNotChangeTheFingerprint() {
        assertThat(fingerprint(List.of(1L, 2L))).isEqualTo(
                ReservationService.fingerprint(request(List.of(2L, 1L)), List.of(1L, 2L)));
    }

    @Test
    void sameIdWithAnotherWindowOrCourtsIsRejectedAsMismatch() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, fingerprint(List.of(1L)), false)));

        assertThatThrownBy(() -> service.reserve(request(List.of(1L, 2L))))
                .satisfies(error -> {
                    assertThat(status(error)).isEqualTo(409);
                    assertThat(code(error)).isEqualTo(ReservationService.MISMATCH);
                });
        verify(store, never()).insert(any(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    void replayOfACancelledOrRolledBackIdIsClosedNotRevived() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.CANCELLED, fingerprint(List.of(1L)), false)));
        assertThatThrownBy(() -> service.reserve(request(List.of(1L))))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ReservationService.CLOSED));

        var tombstone = new Reservation(ID, null, null, null, State.CANCELLED, null, null, null, null,
                OffsetDateTime.now(), CancelCause.ROLLBACK, false);
        when(store.find(ID)).thenReturn(Optional.of(tombstone));
        assertThatThrownBy(() -> service.reserve(request(List.of(1L))))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ReservationService.CLOSED));
    }

    @Test
    void replayAfterTheLeaseExpiredReleasesItAndReportsExpired() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, fingerprint(List.of(1L)), true)));
        when(store.expire(ID)).thenReturn(true);

        assertThatThrownBy(() -> service.reserve(request(List.of(1L))))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ReservationService.EXPIRED));
        verify(store).expire(ID);
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void overlapWithAnotherLockOrBookingPropagatesAndStoresNothing() {
        when(store.find(ID)).thenReturn(Optional.empty());
        var conflict = new ErrorResponseException(HttpStatus.CONFLICT);
        doThrow(conflict).when(locks).requireNoLockOverlap(anyList(), any(), any());

        assertThatThrownBy(() -> service.reserve(request(List.of(1L)))).isSameAs(conflict);
        verify(store, never()).insert(any(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
        verify(store, never()).insertLocks(any(), anyLong(), anyList(), any(), any(), anyLong(), any());

        doThrow(conflict).when(locks).requireNoBookings(anyList(), any(), any());
        assertThatThrownBy(() -> service.reserve(request(List.of(1L)))).isSameAs(conflict);
    }

    @Test
    void foreignBranchUnknownOrInactiveCourtsAndBadWindowsAreRefusedBeforeAnyWrite() {
        when(branches.findOwned(BRANCH, 99)).thenReturn(Optional.empty());
        var foreign = new ReserveRequest(ID, BRANCH, 99L, List.of(1L), START, END, null, null);
        assertThatThrownBy(() -> service.reserve(foreign)).satisfies(error -> assertThat(status(error)).isEqualTo(404));

        assertThatThrownBy(() -> service.reserve(request(List.of(1L, 9L))))
                .satisfies(error -> assertThat(status(error)).isEqualTo(404));

        when(courts.listByBranch(BRANCH)).thenReturn(List.of(court(1, "INACTIVE")));
        assertThatThrownBy(() -> service.reserve(request(List.of(1L))))
                .satisfies(error -> assertThat(code(error)).isEqualTo("COURT_INACTIVE"));

        var offGrid = new ReserveRequest(ID, BRANCH, ADMIN, List.of(1L), START.plusMinutes(10), END, null, null);
        assertThatThrownBy(() -> service.reserve(offGrid)).satisfies(error -> assertThat(status(error)).isEqualTo(400));
        var backwards = new ReserveRequest(ID, BRANCH, ADMIN, List.of(1L), END, START, null, null);
        assertThatThrownBy(() -> service.reserve(backwards)).satisfies(error -> assertThat(status(error)).isEqualTo(400));
        var past = new ReserveRequest(ID, BRANCH, ADMIN, List.of(1L), NOW.minusDays(2), NOW.minusDays(1), null, null);
        assertThatThrownBy(() -> service.reserve(past)).satisfies(error -> assertThat(status(error)).isEqualTo(400));
        var duplicate = new ReserveRequest(ID, BRANCH, ADMIN, List.of(1L, 1L), START, END, null, null);
        assertThatThrownBy(() -> service.reserve(duplicate)).satisfies(error -> assertThat(status(error)).isEqualTo(400));
        var tooLong = new ReserveRequest(ID, BRANCH, ADMIN, List.of(1L), START, START.plusDays(32), null, null);
        assertThatThrownBy(() -> service.reserve(tooLong)).satisfies(error -> assertThat(status(error)).isEqualTo(400));
        verify(store, never()).insert(any(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    void commitMovesReservedToCommittedAndIsIdempotent() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, "x", false)),
                Optional.of(row(State.RESERVED, "x", false)), Optional.of(row(State.COMMITTED, "x", false)));
        when(store.commit(ID)).thenReturn(true);

        var first = service.commit(ID);

        assertThat(first.state()).isEqualTo(State.COMMITTED);
        verify(store).lockBranch(BRANCH);
        verify(events).publishEvent(any(Object.class));

        when(store.find(ID)).thenReturn(Optional.of(row(State.COMMITTED, "x", false)));
        assertThat(service.commit(ID).state()).isEqualTo(State.COMMITTED);
        verify(store).commit(ID); // not called again
        verify(events).publishEvent(any(Object.class)); // no second event
    }

    @Test
    void commitAfterLeaseExpiryReleasesTheCourtsAndIsRefused() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, "x", true)));
        when(store.commit(ID)).thenReturn(false);

        assertThatThrownBy(() -> service.commit(ID))
                .satisfies(error -> {
                    assertThat(status(error)).isEqualTo(409);
                    assertThat(code(error)).isEqualTo(ReservationService.EXPIRED);
                });
        verify(store).expire(ID);
    }

    @Test
    void commitOfUnknownCancelledOrTombstonedReservationIsRefused() {
        when(store.find(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.commit(ID)).satisfies(error -> assertThat(status(error)).isEqualTo(404));

        when(store.find(ID)).thenReturn(Optional.of(row(State.CANCELLED, "x", false)));
        assertThatThrownBy(() -> service.commit(ID))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ReservationService.CLOSED));
        verify(store, never()).commit(ID);
    }

    @Test
    void rollbackReleasesOnceAndIsIdempotent() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.RESERVED, "x", false)),
                Optional.of(row(State.CANCELLED, "x", false)));
        when(store.cancel(ID, CancelCause.ROLLBACK)).thenReturn(true);

        assertThat(service.rollback(ID).state()).isEqualTo(State.CANCELLED);
        verify(events).publishEvent(any(Object.class));

        when(store.find(ID)).thenReturn(Optional.of(row(State.CANCELLED, "x", false)));
        when(store.cancel(ID, CancelCause.ROLLBACK)).thenReturn(false);
        assertThat(service.rollback(ID).state()).isEqualTo(State.CANCELLED);
        verify(events).publishEvent(any(Object.class)); // unchanged: second rollback is a no-op
    }

    @Test
    void rollbackOfAnUnknownIdLeavesATombstoneSoALateReserveCannotWin() {
        var tombstone = new Reservation(ID, null, null, null, State.CANCELLED, null, null, null, null,
                OffsetDateTime.now(), CancelCause.ROLLBACK, false);
        when(store.find(ID)).thenReturn(Optional.empty(), Optional.of(tombstone));

        assertThat(service.rollback(ID).state()).isEqualTo(State.CANCELLED);
        verify(store).insertTombstone(ID);
        verify(store, never()).lockBranch(anyLong());
    }

    @Test
    void rollbackAlsoCompensatesACommittedReservation() {
        when(store.find(ID)).thenReturn(Optional.of(row(State.COMMITTED, "x", false)),
                Optional.of(row(State.CANCELLED, "x", false)));
        when(store.cancel(ID, CancelCause.ROLLBACK)).thenReturn(true);

        assertThat(service.rollback(ID).state()).isEqualTo(State.CANCELLED);
    }

    @Test
    void sweeperReleasesEachExpiredReservationUnderItsBranchLock() {
        UUID other = UUID.randomUUID();
        when(store.expired(50)).thenReturn(List.of(new Expired(ID, BRANCH), new Expired(other, 4)));
        when(store.expire(ID)).thenReturn(true);
        when(store.expire(other)).thenReturn(false); // committed or released concurrently
        when(store.find(ID)).thenReturn(Optional.of(row(State.CANCELLED, "x", false)));
        when(store.find(other)).thenReturn(Optional.of(row(State.COMMITTED, "x", false)));

        assertThat(service.expireDue()).isEqualTo(1);
        verify(store).lockBranch(BRANCH);
        verify(store).lockBranch(4);
    }
}
