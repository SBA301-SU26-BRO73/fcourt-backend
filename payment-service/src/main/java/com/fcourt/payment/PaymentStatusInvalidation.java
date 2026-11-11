package com.fcourt.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class PaymentStatusInvalidation {
    private final PaymentStore store;
    private final PaymentStatusCache cache;
    PaymentStatusInvalidation(PaymentStore store,PaymentStatusCache cache){this.store=store;this.cache=cache;}
    @TransactionalEventListener(fallbackExecution=true)
    public void changed(PaymentStatusChanged event){
        try {
            var version=store.jdbc().queryForObject("SELECT status_version FROM payments WHERE booking_id=?",Long.class,event.bookingId());
            if(version!=null)cache.invalidate(event.bookingId(),version);
        } catch(org.springframework.dao.DataAccessException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment cache refresh pending for booking {}",event.bookingId());
        }
    }
}
