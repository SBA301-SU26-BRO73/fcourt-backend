package com.fcourt.facility.events;

import com.fcourt.facility.api.LockDtos.LockResponse;
import java.util.Collection;

/**
 * Public schedule invalidations for realtime clients (SCRUM-108). Implementations must be best-effort:
 * a notification outage never fails a committed lock command, because clients resync over HTTP.
 */
public interface LockScheduleEvents {
    LockScheduleEvents NONE = new LockScheduleEvents() {
        @Override public void created(Collection<LockResponse> locks) { }
        @Override public void removed(Collection<LockResponse> locks) { }
    };

    /** Call only after the transaction that inserted the locks has committed. */
    void created(Collection<LockResponse> locks);

    /** Call only after the transaction that cancelled the locks has committed. */
    void removed(Collection<LockResponse> locks);
}
