package com.fcourt.facility.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.events.LockScheduleEvents;
import com.fcourt.facility.integration.SubscriptionLimitClient;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SCRUM-108: a court status change or delete announces a SLOT_CHANGED invalidation after commit. */
class CourtStatusEventsTest {
    static final long ADMIN = 7;
    BranchStore branches = mock(BranchStore.class);
    CourtStore courts = mock(CourtStore.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    LockScheduleEvents events = mock(LockScheduleEvents.class);
    CourtService service;

    @BeforeEach
    void setUp() {
        service = new CourtService(branches, courts, mock(BranchEventWriter.class), mock(SubscriptionLimitClient.class),
                new TransactionTemplate(txManager));
        service.setScheduleEvents(events);
        var court = new CourtResponse(5, 3, "Court 5", 1, null, null, null, "ACTIVE", Instant.now(), Instant.now());
        when(courts.find(5)).thenReturn(Optional.of(new OwnedCourt(court, ADMIN, "ACTIVE")));
        when(branches.lockOwned(3, ADMIN)).thenReturn(true);
    }

    @Test
    void statusChangePublishesAfterCommit() {
        service.changeStatus(ADMIN, 5, "MAINTENANCE");

        var order = inOrder(courts, txManager, events);
        order.verify(courts).updateStatus(5, "MAINTENANCE");
        order.verify(txManager).commit(any());
        order.verify(events).courtStatusChanged(5);
    }

    @Test
    void deletePublishesAfterCommit() {
        service.delete(ADMIN, 5);

        var order = inOrder(courts, txManager, events);
        order.verify(courts).softDelete(5);
        order.verify(txManager).commit(any());
        order.verify(events).courtStatusChanged(5);
    }

    @Test
    void rolledBackStatusChangePublishesNothing() {
        doThrow(new IllegalStateException("db down")).when(courts).updateStatus(5, "INACTIVE");

        try {
            service.changeStatus(ADMIN, 5, "INACTIVE");
        } catch (IllegalStateException expected) {
            verify(txManager).rollback(any());
        }
        verifyNoInteractions(events);
    }
}
