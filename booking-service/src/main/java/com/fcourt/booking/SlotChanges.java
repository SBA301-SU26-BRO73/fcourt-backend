package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public invalidation only: never include hold tokens, guests, bank data or booking IDs. */
@Component
@ConditionalOnProperty(name="booking.realtime.enabled", havingValue="true")
class SlotChanges implements MessageListener {
    static final String CHANNEL = "fcourt:slot-changes";
    /** Allow-listed event types; anything else is forwarded as the generic SLOT_CHANGED invalidation. */
    static final java.util.Set<String> TYPES=java.util.Set.of("SLOT_CHANGED","HOLD_CREATED","HOLD_RELEASED","HOLD_EXPIRED","LOCK_CREATED","LOCK_REMOVED");
    static final java.util.Set<String> LOCK_TYPES=java.util.Set.of("OUT_OF_SERVICE","PRIVATE_EVENT");
    private final ObjectMapper json;
    private final SimpMessagingTemplate broker;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    SlotChanges(ObjectMapper json, SimpMessagingTemplate broker, StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.json=json; this.broker=broker; this.redis=redis; this.jdbc=jdbc;
    }
    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            if(message.getBody().length>1024) return;
            var event=json.readTree(message.getBody());
            long court=event.path("courtId").asLong();
            String date=event.path("date").asText();
            if(court<=0 || !LocalDate.parse(date).toString().equals(date)) return;
            var payload=new java.util.LinkedHashMap<String,Object>();
            payload.put("courtId",court);
            payload.put("date",date);
            String type=event.path("type").asText("");
            if(!TYPES.contains(type)) type="SLOT_CHANGED";
            payload.put("type",type);
            String lockType=event.path("lockType").asText("");
            if(type.startsWith("LOCK_") && LOCK_TYPES.contains(lockType)) payload.put("lockType",lockType);
            broker.convertAndSend("/topic/court-slots/"+court, payload);
        } catch(Exception ignored) {
            // Redis Pub/Sub is advisory. Clients resync on connect and periodically.
        }
    }
    void afterCommit(long bookingId) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    for(var row:jdbc.queryForList("SELECT DISTINCT court_id,booking_date FROM booking_slots WHERE booking_id=?",bookingId)) {
                        long court=((Number)row.get("court_id")).longValue();
                        redis.convertAndSend(CHANNEL,json.writeValueAsString(Map.of("courtId",court,"date",row.get("booking_date").toString())));
                    }
                } catch(Exception ignored) {
                    // A notification outage must not turn a committed financial command into an error.
                }
            }
        });
    }
}
