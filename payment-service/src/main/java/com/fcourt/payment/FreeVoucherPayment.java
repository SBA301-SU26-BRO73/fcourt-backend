package com.fcourt.payment;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class FreeVoucherPayment {
    private final PaymentUpstreams upstreams;
    private final CheckoutService checkout;
    private final PaymentStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    FreeVoucherPayment(PaymentUpstreams upstreams, CheckoutService checkout, PaymentStore store, TransactionTemplate tx, Clock clock) {
        this.upstreams = upstreams; this.checkout = checkout; this.store = store; this.tx = tx; this.clock = clock;
    }

    PaymentRecord confirm(long id, Jwt jwt) {
        long customer;
        try { customer = Long.parseLong(jwt.getSubject()); if (customer <= 0) throw new NumberFormatException(); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        String auth = "Bearer " + jwt.getTokenValue();
        var order = upstreams.booking(id, auth, null);
        if (order.id() == null || order.id() != id || order.voucherApplicationId() == null
                || order.totalPrice() == null || order.totalPrice().signum() != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ xác nhận miễn phí cho đơn có voucher giảm còn 0 đồng");
        var previous = store.find(id);
        if (previous != null && "CONFIRMED".equals(previous.status()) && previous.amount().signum() == 0) return previous;
        var info = checkout.checkout(id, auth, null);
        return tx.execute(ignored -> {
            store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, id);
            var existing = store.find(id);
            if (existing != null && "CONFIRMED".equals(existing.status()) && existing.amount().signum() == 0) return existing;
            if (existing != null && (!"PENDING".equals(existing.status()) || existing.submittedAt() != null))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn đã có giao dịch thanh toán khác");
            upstreams.freeze(id, auth, null, info.amount());
            if (!info.expiresAt().isAfter(clock.instant())) throw new ResponseStatusException(HttpStatus.GONE, "Đơn đã hết thời gian giữ chỗ");
            store.jdbc().update("""
                    INSERT INTO payments(booking_id,branch_id,amount,status,checkout,submitted_at,confirmed_at,expires_at)
                    VALUES (?, ?, 0, 'CONFIRMED', ?::jsonb, now(), now(), ?)
                    ON CONFLICT(booking_id) DO UPDATE SET amount=0,status='CONFIRMED',checkout=EXCLUDED.checkout,
                        submitted_at=now(),confirmed_at=now(),updated_at=now()
                    WHERE payments.status='PENDING' AND payments.submitted_at IS NULL
                    """, id, info.branchId(), store.encode(info), java.sql.Timestamp.from(info.expiresAt()));
            CustomerPayments.claim(store.jdbc(), id, customer);
            store.issueTicket(id);
            var confirmed = store.find(id);
            store.event("PaymentConfirmedEvent", confirmed);
            return confirmed;
        });
    }
}
