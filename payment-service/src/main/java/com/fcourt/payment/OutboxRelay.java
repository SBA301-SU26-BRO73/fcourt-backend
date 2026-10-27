package com.fcourt.payment;

import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="payment.events-enabled",havingValue="true",matchIfMissing=true)
class OutboxRelay {
    private final PaymentStore store;
    private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate tx;
    OutboxRelay(PaymentStore store,KafkaTemplate<String,String> kafka,TransactionTemplate tx) {
        this.store=store; this.kafka=kafka; this.tx=tx;
    }
    @Scheduled(fixedDelayString="${payment.outbox-delay-ms:4000}")
    void publish() {
        try {
            tx.executeWithoutResult(ignored -> {
                Boolean locked=store.jdbc().queryForObject("SELECT pg_try_advisory_xact_lock(-8089)",Boolean.class);
                if (!Boolean.TRUE.equals(locked)) return;
                var rows=store.jdbc().queryForList("SELECT * FROM payment_outbox WHERE published_at IS NULL ORDER BY id LIMIT 20");
                for (var row:rows) {
                    try {
                        kafka.send("fcourt.payment.events",row.get("booking_id").toString(),row.get("payload").toString())
                                .get(15,TimeUnit.SECONDS);
                        store.jdbc().update("UPDATE payment_outbox SET published_at=now() WHERE id=?",row.get("id"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    } catch (Exception e) {
                        // Stop at first failure to preserve event order. Consumer deduplicates eventId.
                        LoggerFactory.getLogger(getClass()).warn("Payment outbox delivery pending, event {}",row.get("event_id"));
                        return;
                    }
                }
            });
        } catch (org.springframework.dao.DataAccessException e) {
            LoggerFactory.getLogger(getClass()).warn("Payment outbox database unavailable");
        }
    }
}
