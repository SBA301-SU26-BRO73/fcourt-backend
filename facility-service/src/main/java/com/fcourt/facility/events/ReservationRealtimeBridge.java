package com.fcourt.facility.events;

import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockType;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.service.ReservationService.CourtLocksChanged;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds full-court reservation lock changes (SCRUM-241) into the realtime schedule invalidations (SCRUM-108).
 * CourtLocksChanged is published after the reservation transaction committed. COMMITTED does not change what
 * clients see (the slots were already locked while RESERVED), so only RESERVED and CANCELLED are forwarded.
 */
@Component
class ReservationRealtimeBridge {
    private final ObjectProvider<LockScheduleEvents> events;

    ReservationRealtimeBridge(ObjectProvider<LockScheduleEvents> events) {
        this.events = events;
    }

    @EventListener
    void on(CourtLocksChanged change) {
        if (change.state() == State.COMMITTED) return;
        List<LockResponse> locks = change.courtIds().stream().map(courtId -> new LockResponse(0, change.reservationId(),
                change.branchId(), courtId, LockType.PRIVATE_EVENT, change.startAt(), change.endAt(), "", null, null,
                null, null, 0, null)).toList();
        if (locks.isEmpty()) return;
        LockScheduleEvents target = events.getIfAvailable(() -> LockScheduleEvents.NONE);
        if (change.state() == State.RESERVED) target.created(locks);
        else target.removed(locks);
    }
}
