package com.fcourt.booking;

import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="booking.events-enabled",havingValue="true",matchIfMissing=true)
class BookingOutboxRelay {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate tx;
    BookingOutboxRelay(org.springframework.jdbc.core.JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,TransactionTemplate tx) {
        this.jdbc=jdbc;this.kafka=kafka;this.tx=tx;
    }
    @Scheduled(fixedDelayString="${booking.outbox-delay-ms:4000}")
    void publish() {
        try { tx.executeWithoutResult(ignored->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(-8088)",Boolean.class)))return;
            for(var row:jdbc.queryForList("SELECT * FROM booking_outbox WHERE published_at IS NULL ORDER BY id LIMIT 20")){
                try {
                    kafka.send("fcourt.booking.events",row.get("booking_id").toString(),row.get("payload").toString()).get(15,TimeUnit.SECONDS);
                    jdbc.update("UPDATE booking_outbox SET published_at=now() WHERE id=?",row.get("id"));
                } catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                catch(Exception e){LoggerFactory.getLogger(getClass()).warn("Booking outbox pending, event {}",row.get("event_id"));return;}
            }
        }); }catch(org.springframework.dao.DataAccessException e){LoggerFactory.getLogger(getClass()).warn("Booking outbox database unavailable");}
    }
}
