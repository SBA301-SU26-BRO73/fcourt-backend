package com.fcourt.notification.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.push.PushDeliveryService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(name="notification.push.reminder.enabled", havingValue="true")
class ReminderWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ReminderWorker.class);
    private final SqsClient sqs;
    private final JdbcTemplate jdbc;
    private final PushDeliveryService pushes;
    private final ObjectMapper json;
    private final String queueUrl;
    private final int visibilityTimeout;
    private final TransactionTemplate transactions;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;
    ReminderWorker(SqsClient sqs, JdbcTemplate jdbc, PushDeliveryService pushes, ObjectMapper json,
                   @Value("${notification.push.reminder.queue-url:}") String queueUrl,
                   @Value("${notification.push.reminder.visibility-timeout-seconds:120}") int visibilityTimeout,
                   PlatformTransactionManager transactionManager) {
        this.sqs=sqs; this.jdbc=jdbc; this.pushes=pushes; this.json=json; this.queueUrl=queueUrl;
        this.visibilityTimeout=visibilityTimeout;
        this.transactions = new TransactionTemplate(transactionManager);
    }
    @Override public void start() {
        if (queueUrl.isBlank() || !running.compareAndSet(false,true)) return;
        thread = new Thread(this::poll, "push-reminder-sqs");
        thread.setDaemon(true); thread.start();
    }
    private void poll() {
        while (running.get()) {
            try {
                var batch = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                        .waitTimeSeconds(20).visibilityTimeout(visibilityTimeout).maxNumberOfMessages(10).build());
                for (var message : batch.messages()) {
                    if (!running.get()) break;
                    try {
                        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                                .visibilityTimeout(visibilityTimeout));
                        long id = json.readTree(message.body()).path("bookingId").asLong(-1);
                        if (id < 1) throw new IllegalArgumentException("Reminder has no bookingId");
                        transactions.executeWithoutResult(status -> deliverIfStillActive(id));
                        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle()).build());
                    } catch (Exception error) {
                        log.warn("Push reminder message will be retried or sent to the configured DLQ", error);
                    }
                }
            } catch (Exception error) {
                if (running.get()) log.warn("Push reminder queue poll failed", error);
            }
        }
    }
    void deliverIfStillActive(long bookingId) {
        var rows = jdbc.query("""
                SELECT customer_id FROM booking_reminders
                WHERE booking_id=? AND push_reminder_sent=false
                  AND NOT EXISTS (SELECT 1 FROM cancelled_booking_reminders WHERE booking_id=?)
                FOR UPDATE
                """, (rs, n) -> (Long) rs.getObject(1), bookingId, bookingId);
        if (rows.isEmpty()) return;
        Long userId = rows.getFirst();
        if (userId != null) pushes.sendReminder(bookingId, userId);
        jdbc.update("UPDATE booking_reminders SET push_reminder_sent=true, push_reminder_sent_at=now() WHERE booking_id=?",
                bookingId);
    }
    @Override public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try { thread.join(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
