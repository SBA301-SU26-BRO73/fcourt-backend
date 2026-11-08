package com.fcourt.facility.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.service.ReservationService.CourtLocksChanged;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ReservationRealtimeBridgeTest {
    @SuppressWarnings("unchecked")
    ObjectProvider<LockScheduleEvents> provider(LockScheduleEvents events) {
        ObjectProvider<LockScheduleEvents> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(events);
        return provider;
    }

    CourtLocksChanged change(State state) {
        return new CourtLocksChanged(UUID.randomUUID(), 3, List.of(1L, 2L), LocalDateTime.of(2030, 1, 5, 8, 0),
                LocalDateTime.of(2030, 1, 5, 9, 0), state);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservedIsForwardedAsCreatedCancelledAsRemovedAndCommittedIsIgnored() {
        LockScheduleEvents events = mock(LockScheduleEvents.class);
        var bridge = new ReservationRealtimeBridge(provider(events));

        bridge.on(change(State.RESERVED));
        var created = ArgumentCaptor.forClass(Collection.class);
        verify(events).created(created.capture());
        assertThat(((Collection<LockResponse>) created.getValue())).extracting(LockResponse::courtId).containsExactly(1L, 2L);

        bridge.on(change(State.COMMITTED));
        verify(events, never()).removed(any());

        bridge.on(change(State.CANCELLED));
        verify(events).removed(any());
    }
}
