package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.api.LockDtos.BranchLockRequest;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockSlotsRequest;
import com.fcourt.facility.api.LockDtos.LockType;
import com.fcourt.facility.events.LockScheduleEvents;
import com.fcourt.facility.integration.SlotAvailabilityClient;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import com.fcourt.facility.persistence.LockStore;
import com.fcourt.facility.persistence.LockStore.NewLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** SCRUM-108: lock commands announce schedule changes only after the database transaction committed. */
class LockRealtimeEventsTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 8, 0);
    static final LocalDate DAY = LocalDate.of(2030, 1, 5);
    static final long ADMIN = 7;

    BranchStore branches = mock(BranchStore.class);
    CourtStore courts = mock(CourtStore.class);
    LockStore locks = mock(LockStore.class);
    CourtService courtService = mock(CourtService.class);
    SlotAvailabilityClient availability = mock(SlotAvailabilityClient.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    LockScheduleEvents events = mock(LockScheduleEvents.class);
    LockService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new LockService(branches, courts, locks, courtService, availability,
                new TransactionTemplate(txManager), clock);
        service.setEvents(events);
        when(branches.lockOwned(anyLong(), anyLong())).thenReturn(true);
        when(locks.overlapping(anyLong(), any(), any())).thenReturn(List.of());
        when(availability.conflicts(anyLong(), any())).thenReturn(List.of());
        when(locks.insert(any())).thenAnswer(call -> {
            NewLock lock = call.getArgument(0);
            return response(1, lock.groupId(), lock.courtId(), lock.type(), lock.startAt(), lock.endAt());
        });
    }

    static LockResponse response(long id, UUID group, long court, LockType type, LocalDateTime from, LocalDateTime to) {
        return new LockResponse(id, group, 3, court, type, from, to, "internal reason", "notes", null, null, null,
                ADMIN, OffsetDateTime.now());
    }

    static CourtResponse court(long id) {
        return new CourtResponse(id, 3, "Court " + id, 1, null, null, null, "ACTIVE", Instant.now(), Instant.now());
    }

    void ownsCourt(long id) {
        when(courtService.owned(ADMIN, id)).thenReturn(new OwnedCourt(court(id), ADMIN, "ACTIVE"));
    }

    static LockSlotsRequest request(LockType type) {
        return new LockSlotsRequest(5L, DAY.atTime(13, 0), DAY.atTime(18, 0), type, "Repair", null, null, null, null);
    }

    @Test
    void lockCourtPublishesOnlyAfterCommit() {
        ownsCourt(5);
        LockResponse created = service.lockCourt(ADMIN, request(LockType.PRIVATE_EVENT));

        var order = inOrder(txManager, events);
        order.verify(txManager).commit(any());
        order.verify(events).created(List.of(created));
    }

    @Test
    void conflictingLockPublishesNothing() {
        ownsCourt(5);
        var existing = response(40, UUID.randomUUID(), 5, LockType.OUT_OF_SERVICE, DAY.atTime(15, 0), DAY.atTime(16, 0));
        when(locks.overlapping(5, DAY.atTime(13, 0), DAY.atTime(18, 0))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(null))).isNotNull();
        verifyNoInteractions(events);
    }

    @Test
    void failedInsertRollsBackWithoutPublishing() {
        ownsCourt(5);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(locks).insert(any());

        assertThatThrownBy(() -> service.lockCourt(ADMIN, request(null))).isInstanceOf(IllegalStateException.class);
        verify(txManager).rollback(any());
        verifyNoInteractions(events);
    }

    @Test
    void branchWideLockPublishesEveryCreatedLock() {
        var branch = mock(BranchResponse.class);
        when(branch.id()).thenReturn(3L);
        when(branches.findOwned(3L, ADMIN)).thenReturn(Optional.of(branch));
        when(courts.listByBranch(3L)).thenReturn(List.of(court(5), court(6)));

        service.lockBranch(ADMIN, new BranchLockRequest(3L, true, null, DAY.atTime(13, 0), DAY.atTime(18, 0),
                LockType.PRIVATE_EVENT, "Tournament", null, null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<LockResponse>> published = ArgumentCaptor.forClass(Collection.class);
        verify(events).created(published.capture());
        assertThat(published.getValue()).extracting(LockResponse::courtId).containsExactly(5L, 6L);
    }

    @Test
    void unlockPublishesTheRemovedLockAfterCommit() {
        var lock = response(9, UUID.randomUUID(), 5, LockType.OUT_OF_SERVICE, DAY.atTime(13, 0), DAY.atTime(18, 0));
        when(locks.findOwner(9)).thenReturn(Optional.of(new long[] {5, 3, ADMIN}));
        when(locks.findActive(9)).thenReturn(Optional.of(lock));
        when(locks.cancel(9, ADMIN)).thenReturn(1);

        service.unlock(ADMIN, 9);

        var order = inOrder(locks, txManager, events);
        order.verify(locks).cancel(9, ADMIN);
        order.verify(txManager).commit(any());
        order.verify(events).removed(List.of(lock));
    }

    @Test
    void unlockOfAlreadyCancelledLockPublishesNothing() {
        when(locks.findOwner(9)).thenReturn(Optional.of(new long[] {5, 3, ADMIN}));
        when(locks.findActive(9)).thenReturn(Optional.empty());
        when(locks.cancel(9, ADMIN)).thenReturn(0);

        assertThatThrownBy(() -> service.unlock(ADMIN, 9)).isInstanceOf(ResponseStatusException.class);
        verify(events, never()).removed(any());
    }

    @Test
    void unlockGroupPublishesEveryRemovedLock() {
        UUID group = UUID.randomUUID();
        var first = response(1, group, 5, LockType.PRIVATE_EVENT, DAY.atTime(13, 0), DAY.atTime(18, 0));
        var second = response(2, group, 6, LockType.PRIVATE_EVENT, DAY.atTime(13, 0), DAY.atTime(18, 0));
        when(locks.findGroupOwner(group)).thenReturn(Optional.of(new long[] {3, ADMIN}));
        when(locks.findActiveGroup(group)).thenReturn(List.of(first, second));
        when(locks.cancelGroup(group, ADMIN)).thenReturn(2);

        service.unlockGroup(ADMIN, group);

        verify(events).removed(List.of(first, second));
    }
}
