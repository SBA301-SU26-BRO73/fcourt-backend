package com.fcourt.payment;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class PaymentSessions {
    private final PaymentStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    PaymentSessions(PaymentStore store,TransactionTemplate tx,Clock clock) {this.store=store;this.tx=tx;this.clock=clock;}
    void track(CheckoutInfo info) {
        tx.executeWithoutResult(ignored -> {
            lock(info.bookingId());
            var existing=store.find(info.bookingId());
            if (existing != null) {
                if ("CANCELLED".equals(existing.status())) throw new ResponseStatusException(HttpStatus.GONE,"Checkout expired");
                if (existing.submittedAt()!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Receipt already submitted");
                return;
            }
            if (!info.expiresAt().isAfter(clock.instant())) throw new ResponseStatusException(HttpStatus.GONE,"Checkout expired");
            store.jdbc().update("""
                    INSERT INTO payments(booking_id,branch_id,amount,checkout,expires_at) VALUES(?,?,?,?::jsonb,?)
                    ON CONFLICT(booking_id) DO NOTHING
                    """,info.bookingId(),info.branchId(),info.amount(),store.encode(info),java.sql.Timestamp.from(info.expiresAt()));
        });
    }
    @Scheduled(fixedDelayString="${payment.expiry-delay-ms:60000}")
    void expire() {
        // Bounded batch, all nodes use the same per-booking lock as upload and approval.
        var ids=store.jdbc().queryForList("""
                SELECT booking_id FROM payments WHERE status='PENDING' AND submitted_at IS NULL
                    AND booking_id IS NOT NULL AND expires_at <= ?
                ORDER BY id LIMIT 200
                """,Long.class,java.sql.Timestamp.from(clock.instant()));
        for(long id:ids) expireOne(id);
    }
    void expireOne(long id) {
        tx.executeWithoutResult(ignored -> {
            lock(id);
            var current=store.find(id);
            if (current==null || current.submittedAt()!=null || !"PENDING".equals(current.status())
                    || current.checkout()==null || current.checkout().expiresAt().isAfter(clock.instant())) return;
            store.jdbc().update("UPDATE payments SET status='CANCELLED',updated_at=now() WHERE booking_id=?",id);
            store.event("BookingCancelledEvent",store.find(id));
        });
    }
    private void lock(long id) {store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,id);}
}
