package com.fcourt.facility.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.service.SlotWindows;
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
    private final ObjectMapper json;

    RedisLockScheduleEvents(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
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
