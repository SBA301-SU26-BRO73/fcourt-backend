package com.fcourt.payment;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class PaymentApprovalService {
    private final PaymentStore store;
    private final BranchOwnership ownership;
    private final TransactionTemplate tx;
    PaymentApprovalService(PaymentStore store,BranchOwnership ownership,TransactionTemplate tx) {
        this.store=store; this.ownership=ownership; this.tx=tx;
    }
    List<PaymentRecord> pending(long branchId,int offset,Jwt jwt) {
        ownership.require(branchId,jwt);
        return store.jdbc().query("""
                SELECT * FROM payments WHERE branch_id=? AND status='PENDING' AND submitted_at IS NOT NULL
                ORDER BY submitted_at,id LIMIT 50 OFFSET ?
                """,store::row,branchId,offset);
    }
    PaymentRecord decide(long paymentId,boolean confirm,String reason,Jwt jwt) {
        var found=store.jdbc().query("SELECT * FROM payments WHERE id=?",store::row,paymentId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        ownership.require(found.getFirst().branchId(),jwt);
        if (!confirm && (reason == null || reason.isBlank() || reason.length()>500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rejection reason required (1-500 characters)");
        }
        return tx.execute(ignored -> {
            long bookingId=found.getFirst().bookingId();
            // Same lock domain as submissions and expiry, taken before a row lock.
            store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,bookingId);
            var payment=store.jdbc().query("SELECT * FROM payments WHERE id=? FOR UPDATE",store::row,paymentId).getFirst();
            String desired=confirm ? "CONFIRMED" : "REJECTED";
            if (desired.equals(payment.status())) {
                if (confirm && payment.checkInCode()==null) {
                    store.issueTicket(bookingId);var issued=store.find(bookingId);
                    store.event("PaymentConfirmedEvent",issued);return issued;
                }
                return payment;
            }
            if (!"PENDING".equals(payment.status()) || payment.submittedAt()==null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment is not awaiting approval");
            }
            store.jdbc().update("""
                    UPDATE payments SET status=?,confirmed_at=now(),confirmed_by=?,rejection_reason=?,updated_at=now() WHERE id=?
                    """,desired,ownership.actor(jwt),confirm ? null : reason.strip(),paymentId);
            if (confirm) store.issueTicket(bookingId);
            var saved=store.find(bookingId);
            store.event(confirm ? "PaymentConfirmedEvent" : "PaymentRejectedEvent",saved);
            return saved;
        });
    }
}
