package com.fcourt.facility.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.service.SlotWindows;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Publishes one invalidation per (court, date) a lock window touches on the Redis channel that
 * booking-service fans out to STOMP. Payload: {courtId, date, type, lockType}. Reasons, notes,
 * contacts, branch/admin ids and lock ids never leave facility-service.
 */
class RedisLockScheduleEvents implements LockScheduleEvents {
    static final String CHANNEL = "fcourt:slot-changes";
    private static final Logger log = LoggerFactory.getLogger(RedisLockScheduleEvents.class);
    private final StringRedisTemplate redis;
    /** Days ahead announced for a court status change (matches the 31-day lock horizon). */
    static final int STATUS_DAYS = 30;
    private final ObjectMapper json;
    private final Clock clock;

    RedisLockScheduleEvents(StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    @Override public void courtStatusChanged(long courtId) {
        try {
            LocalDate today = LocalDate.now(clock);
            for (int day = 0; day < STATUS_DAYS; day++) {
                var event = new LinkedHashMap<String, Object>();
                event.put("courtId", courtId);
                event.put("date", today.plusDays(day).toString());
                event.put("type", "SLOT_CHANGED");
                redis.convertAndSend(CHANNEL, json.writeValueAsString(event));
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Court status notification not sent: {}", e.toString());
        }
    }

    @Override public void created(Collection<LockResponse> locks) {
        publish("LOCK_CREATED", locks);
    }

    @Override public void removed(Collection<LockResponse> locks) {
        publish("LOCK_REMOVED", locks);
    }

    private void publish(String type, Collection<LockResponse> locks) {
        try {
            Set<String> messages = new LinkedHashSet<>();
            for (LockResponse lock : locks) {
                for (var date : SlotWindows.dates(lock.startAt(), lock.endAt())) {
                    var event = new LinkedHashMap<String, Object>();
                    event.put("courtId", lock.courtId());
                    event.put("date", date.toString());
                    event.put("type", type);
                    event.put("lockType", lock.type().name());
                    messages.add(json.writeValueAsString(event));
                }
            }
            for (String message : messages) redis.convertAndSend(CHANNEL, message);
        } catch (JsonProcessingException | RuntimeException e) {
            // Committed lock commands must not fail because an advisory notification could not be sent.
            log.warn("Lock schedule notification not sent: {}", e.toString());
        }
    }
}
