package com.fcourt.payment;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class ReceiptService {
    private final CheckoutService checkout;
    private final PaymentUpstreams upstreams;
    private final PaymentStore store;
    private final ReceiptStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;
    ReceiptService(CheckoutService checkout, PaymentUpstreams upstreams, PaymentStore store,
            ReceiptStorage storage, TransactionTemplate tx, Clock clock) {
        this.checkout=checkout; this.upstreams=upstreams; this.store=store;
        this.storage=storage; this.tx=tx; this.clock=clock;
    }
    PaymentRecord status(long id, String auth, String hold) {
        authorize(id, auth, hold);
        var payment=store.find(id);
        if (payment == null || (payment.submittedAt()==null && "PENDING".equals(payment.status()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not submitted");
        return payment;
    }
    PaymentRecord ticket(long id,String auth,String hold) {
        var booking=authorize(id,auth,hold);
        if (booking.status()==null) throw PaymentUpstreams.unavailable();
        if ("CANCELLED".equals(booking.status())) throw new ResponseStatusException(HttpStatus.GONE,"Booking cancelled");
        if (!java.util.Set.of("CONFIRMED","CHECKED_IN","COMPLETED").contains(booking.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Booking confirmation is not yet synchronized");
        }
        var payment=store.find(id);
        if (payment==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        return payment;
    }
    PaymentRecord upload(long id, String auth, String hold, MultipartFile file) {
        authorize(id,auth,hold);
        var previous=store.find(id);
        if (previous != null && "CANCELLED".equals(previous.status())) throw new ResponseStatusException(HttpStatus.GONE,"Checkout expired");
        if (previous != null && previous.submittedAt()!=null) return previous; // Lost HTTP response: retry is safe and never replaces a bill.
        byte[] bytes=ReceiptValidator.read(file);
        var info=checkout.checkout(id,auth,hold);
        String url=storage.upload(bytes);
        return tx.execute(ignored -> {
            // Serializes competing uploads, including across payment-service instances.
            store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class,id);
            var existing=store.find(id);
            if (existing != null && "CANCELLED".equals(existing.status())) throw new ResponseStatusException(HttpStatus.GONE,"Checkout expired");
            if (existing != null && existing.submittedAt()!=null) return existing;
            if (!info.expiresAt().isAfter(clock.instant())) {
                throw new ResponseStatusException(HttpStatus.GONE,"Hold expired during upload");
            }
            var saved=store.submit(info,url);
            store.event("PaymentSubmittedEvent",saved);
            return saved;
        });
    }
    PaymentUpstreams.Booking authorize(long id, String auth, String hold) {
        if ((auth == null || !auth.startsWith("Bearer ") || auth.length() <= 7)
                && (hold == null || hold.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication or hold token required");
        }
        var booking=upstreams.booking(id,auth,hold);
        if (booking.id() == null || booking.id() != id) throw PaymentUpstreams.unavailable();
        return booking;
    }
}
