package com.fcourt.pos.service;

import com.fcourt.pos.integration.PosUpstreams;
import com.fcourt.pos.persistence.PosStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PosDispatchWorker {
    private final JdbcTemplate jdbc;
    private final PosStore store;
    private final PosCommands commands;
    private final PosUpstreams booking;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final String topic;
    private final boolean enabled;

    public PosDispatchWorker(JdbcTemplate jdbc, PosStore store, PosCommands commands, PosUpstreams booking,
                              KafkaTemplate<String, String> kafka, PlatformTransactionManager manager,
                              @Value("${pos.events-topic}") String topic, @Value("${pos.dispatch-enabled}") boolean enabled) {
        this.jdbc = jdbc; this.store = store; this.commands = commands; this.booking = booking; this.kafka = kafka;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(35);
        this.topic = topic; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${pos.dispatch-delay-ms:1000}")
    void tick() { if (enabled) drain(); }

    public void drain() {
        RuntimeException failure = null;
        var pending = jdbc.queryForList("SELECT event_id FROM pos_outbox WHERE published_at IS NULL ORDER BY CASE WHEN kind = 'CHARGE' THEN 0 ELSE 1 END, created_at, event_id LIMIT 10", UUID.class);
        for (UUID eventId : pending) {
            try { transaction.executeWithoutResult(status -> publishOne(eventId)); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }

    private void publishOne(UUID eventId) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(8091, 1)", Boolean.class))) return;
            for (var row : jdbc.queryForList("SELECT * FROM pos_outbox WHERE event_id = ? AND published_at IS NULL FOR UPDATE", eventId)) {
                long id = ((Number) row.get("order_id")).longValue();
                if ("CHARGE".equals(row.get("kind"))) {
                    var order = store.order(id, true);
                    if ("PENDING_CHARGE".equals(order.status())) {
                        try {
                            booking.attachCharge(order);
                            jdbc.update("UPDATE orders SET status = 'ON_BOOKING', version = version + 1 WHERE id = ?", id);
                            commands.event("PosBookingChargeAttachedEvent", store.order(id, false));
                        } catch (PosUpstreams.ChargeRejected rejected) {
                            for (var item : order.items()) {
                                jdbc.update("UPDATE products SET stock_quantity = stock_quantity + ?, updated_at = now() WHERE id = ?", item.quantity(), item.productId());
                                commands.stockMovement(item.productId(), order.cashierUserId(), UUID.fromString(order.orderCode().substring(4)),
                                        "RESTOCK", item.quantity(), "booking charge permanently rejected", id);
                            }
                            jdbc.update("UPDATE orders SET status = 'CANCELLED', version = version + 1 WHERE id = ?", id);
                            commands.event("PosOrderCancelledEvent", store.order(id, false));
                        }
                    }
                } else {
                    try { kafka.send(topic, store.order(id, false).orderCode(), row.get("payload").toString()).get(3, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("POS dispatch interrupted", error); }
                    catch (Exception error) { throw new IllegalStateException("POS event acknowledgement unavailable; retained", error); }
                }
                jdbc.update("UPDATE pos_outbox SET published_at = now() WHERE event_id = ?", row.get("event_id"));
            }
    }
}
