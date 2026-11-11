package com.fcourt.payment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class RefundService {
    private final PaymentStore store;
    private final PaymentUpstreams authority;
    private final TransactionTemplate tx;
    RefundService(PaymentStore store,PaymentUpstreams authority,TransactionTemplate tx){this.store=store;this.authority=authority;this.tx=tx;}
    record Request(@Positive long bookingId,@NotBlank @Size(max=100) String bankName,
        @NotBlank @Pattern(regexp="970[0-9]{3}") String bankBin,
        @NotBlank @Pattern(regexp="[0-9]{6,20}") String bankAccountNumber,
        @NotBlank @Size(min=2,max=150) String bankAccountName,@NotBlank @Size(max=500) String reason) {}
    record State(long bookingId,BigDecimal amount,boolean eligible,RefundView request) {}
    RefundView request(Request request,Jwt jwt){
        long owner=CustomerPayments.actor(jwt);
        var booking=eligible(request.bookingId(),jwt);
        return tx.execute(s->{
            store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,request.bookingId());
            var payment=store.find(request.bookingId());requirePayment(payment,booking);
            var existing=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? FOR UPDATE",request.bookingId());
            if(!existing.isEmpty()){
                var r=existing.getFirst();
                if(!(r.get("customer_id") instanceof Number n) || n.longValue()!=owner)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Refund not found");
                if(!request.bankBin().equals(r.get("bank_bin")) || !request.bankAccountNumber().equals(r.get("refund_bank_account_number")) || !request.bankAccountName().strip().equals(r.get("refund_bank_account_name")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"A refund already exists for this booking");
                return row(r);
            }
            Long id=store.jdbc().queryForObject("""
              INSERT INTO refund_requests(booking_id,amount,reason,refund_bank_name,bank_bin,refund_bank_account_number,refund_bank_account_name,customer_id,branch_id)
              VALUES(?,?,?,?,?,?,?,?,?) RETURNING id
              """,Long.class,request.bookingId(),booking.refundAmount(),request.reason().strip(),request.bankName().strip(),request.bankBin(),request.bankAccountNumber(),request.bankAccountName().strip(),owner,payment.branchId());
            // Tells the branch owner (notification-service) a refund is waiting; only for a newly created request.
            UUID eventId=UUID.randomUUID();
            store.jdbc().update("INSERT INTO payment_outbox(event_id,booking_id,event_type,payload) VALUES (?,?,?,?::jsonb)",eventId,request.bookingId(),"RefundRequestedEvent",
                store.encode(Map.of("eventId",eventId,"type","RefundRequestedEvent","refund",Map.of("id",id,"bookingId",request.bookingId(),"branchId",payment.branchId(),"customerId",owner,"amount",booking.refundAmount(),"status","PENDING"))));
            return row(store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE id=?",id));
        });
    }
    State state(long id,Jwt jwt){
        long owner=CustomerPayments.actor(jwt);
        var existing=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? AND customer_id=?",id,owner);
        if(!existing.isEmpty()){var refund=row(existing.getFirst());return new State(id,refund.amount(),false,refund);}
        var booking=eligible(id,jwt);requirePayment(store.find(id),booking);
        return new State(id,booking.refundAmount(),true,null);
    }
    private PaymentUpstreams.Booking eligible(long id,Jwt jwt){
        var booking=authority.booking(id,"Bearer "+jwt.getTokenValue(),null);
        if(booking==null || booking.id()==null || booking.id()!=id)throw PaymentUpstreams.unavailable();
        if(!"CANCELLED".equals(booking.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Booking must be cancelled first");
        var amount=booking.refundAmount();
        if(amount==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Cancellation refund policy unavailable");
        if(amount.signum()<=0 || amount.stripTrailingZeros().scale()>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Booking has no refund entitlement");
        return booking;
    }
    private void requirePayment(PaymentRecord payment,PaymentUpstreams.Booking booking){
        if(payment==null || !"CONFIRMED".equals(payment.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirmed payment required");
        if(booking.branchId()==null || booking.branchId()!=payment.branchId() || booking.totalPrice()==null
            || booking.totalPrice().compareTo(payment.amount())!=0 || booking.refundAmount().compareTo(payment.amount())>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund amount or booking identity mismatch");
    }
    static RefundView row(Map<String,Object> r){
        return new RefundView(((Number)r.get("id")).longValue(),((Number)r.get("booking_id")).longValue(),(BigDecimal)r.get("amount"),r.get("status").toString(),
            (String)r.get("refund_bank_name"),(String)r.get("bank_bin"),(String)r.get("refund_bank_account_number"),(String)r.get("refund_bank_account_name"),
            (String)r.get("reason"),(String)r.get("admin_note"),(String)r.get("refund_proof_image_url"),
            ((java.sql.Timestamp)r.get("created_at")).toInstant(),((java.sql.Timestamp)r.get("updated_at")).toInstant());
    }
}
