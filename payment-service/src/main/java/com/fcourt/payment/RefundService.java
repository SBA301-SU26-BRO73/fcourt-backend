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
                if(row(r).bankInfoMissing()) {
                    updateBank(((Number)r.get("id")).longValue(),r,request.bankName(),request.bankBin(),request.bankAccountNumber(),request.bankAccountName());
                    return row(store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE booking_id=?",request.bookingId()));
                }
                if(!request.bankBin().equals(r.get("bank_bin")) || !request.bankAccountNumber().equals(r.get("refund_bank_account_number")) || !request.bankAccountName().strip().equals(r.get("refund_bank_account_name")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"A refund already exists for this booking");
                return row(r);
            }
            return create(request.bookingId(),booking.refundAmount(),request.reason(),owner,payment,
                booking.customerPhone(),request.bankName(),request.bankBin(),request.bankAccountNumber(),request.bankAccountName());
        });
    }
    State state(long id,Jwt jwt){
        long owner=CustomerPayments.actor(jwt);
        var existing=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? AND customer_id=?",id,owner);
        if(!existing.isEmpty()){var refund=row(existing.getFirst());return new State(id,refund.amount(),"PENDING".equals(refund.status()) && refund.bankInfoMissing(),refund);}
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
        requirePayment(payment,booking.branchId(),booking.totalPrice(),booking.refundAmount());
    }
    private void requirePayment(PaymentRecord payment,Long branch,BigDecimal total,BigDecimal amount){
        if(payment==null || !"CONFIRMED".equals(payment.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirmed payment required");
        if(branch==null || branch!=payment.branchId() || total==null || amount==null || amount.signum()<=0
            || amount.stripTrailingZeros().scale()>0 || total.compareTo(payment.amount())!=0 || amount.compareTo(payment.amount())>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund amount or booking identity mismatch");
    }

    record GuestIdentity(
        @NotBlank @Pattern(regexp="(?i)FC-?[0-9]{8}") String bookingCode,
        @NotBlank @Pattern(regexp="\\+?[0-9]{9,15}") String phone) {}
    record GuestRequest(
        @NotBlank @Pattern(regexp="(?i)FC-?[0-9]{8}") String bookingCode,
        @NotBlank @Pattern(regexp="\\+?[0-9]{9,15}") String phone,
        @NotBlank @Size(max=100) String bankName,
        @NotBlank @Pattern(regexp="[0-9]{6,20}") String accountNumber,
        @NotBlank @Size(min=2,max=150) String accountHolder,
        @Pattern(regexp="970[0-9]{3}") String bankBin) {}

    RefundView guestRequest(GuestRequest request) {
        // No client-supplied booking/refund id or amount is used. The authority only returns guest bookings.
        var booking=guestEligible(request.bookingCode(),request.phone());
        return tx.execute(s->{
            lock(booking.id());
            var payment=store.find(booking.id());
            requirePayment(payment,booking.branchId(),booking.totalPrice(),booking.refundAmount());
            var rows=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? FOR UPDATE",booking.id());
            if(rows.isEmpty())return create(booking.id(),booking.refundAmount(),"Guest cancellation refund",null,payment,
                request.phone(),request.bankName(),request.bankBin(),request.accountNumber(),request.accountHolder());
            var existing=rows.getFirst();
            if(existing.get("customer_id")!=null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Booking not found");
            long id=((Number)existing.get("id")).longValue();
            updateBank(id,existing,request.bankName(),request.bankBin(),request.accountNumber(),request.accountHolder());
            return row(store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE id=?",id));
        });
    }

    State guestState(GuestIdentity request) {
        var booking=guestEligible(request.bookingCode(),request.phone());
        requirePayment(store.find(booking.id()),booking.branchId(),booking.totalPrice(),booking.refundAmount());
        var rows=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? AND customer_id IS NULL",booking.id());
        var refund=rows.isEmpty()?null:row(rows.getFirst());
        return new State(booking.id(),booking.refundAmount(),refund==null || "PENDING".equals(refund.status()),refund);
    }

    private PaymentUpstreams.GuestBooking guestEligible(String code,String phone) {
        var booking=authority.guestBooking(code,phone);
        if(!"CANCELLED".equals(booking.status()) || !"CONFIRMED".equals(booking.paymentStatus())
                || booking.refundAmount()==null || booking.refundAmount().signum()<=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Booking has no refund entitlement");
        return booking;
    }

    record Cancellation(long bookingId,long branchId,Long customerId,String customerPhone,
                        BigDecimal totalPrice,BigDecimal refundAmount,String reason) {}

    RefundView automatic(Cancellation booking) {
        return tx.execute(s->{
            lock(booking.bookingId());
            var payment=store.find(booking.bookingId());
            // Older cancellation events have no totalPrice; validate their entitlement against the payment.
            requirePayment(payment,booking.branchId(),booking.totalPrice()==null && payment!=null?payment.amount():booking.totalPrice(),booking.refundAmount());
            var rows=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE booking_id=? FOR UPDATE",booking.bookingId());
            if(!rows.isEmpty())return row(rows.getFirst());
            return create(booking.bookingId(),booking.refundAmount(),booking.reason()==null?"Booking cancellation refund":booking.reason(),
                booking.customerId(),payment,booking.customerPhone(),null,null,null,null);
        });
    }

    private void lock(long booking) {
        store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,booking);
    }

    /** Caller holds the per-booking advisory lock. All paths share the existing request and notification contract. */
    private RefundView create(long booking,BigDecimal amount,String reason,Long customer,PaymentRecord payment,
            String phone,String bankName,String bin,String account,String holder) {
        Long id=store.jdbc().queryForObject("""
            INSERT INTO refund_requests(booking_id,amount,reason,refund_bank_name,bank_bin,refund_bank_account_number,
                refund_bank_account_name,customer_id,branch_id,customer_phone)
            VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,booking,amount,reason.strip(),bankName==null?null:bankName.strip(),bin,account,
            holder==null?null:holder.strip(),customer,payment.branchId(),phone);
        UUID eventId=UUID.randomUUID();
        var refund=new LinkedHashMap<String,Object>();
        refund.put("id",id);refund.put("bookingId",booking);refund.put("branchId",payment.branchId());
        refund.put("customerId",customer);refund.put("amount",amount);refund.put("status","PENDING");
        store.jdbc().update("INSERT INTO payment_outbox(event_id,booking_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
            eventId,booking,"RefundRequestedEvent",store.encode(Map.of("eventId",eventId,"type","RefundRequestedEvent","refund",refund)));
        return row(store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE id=?",id));
    }

    private void updateBank(long id,Map<String,Object> existing,String name,String bin,String account,String holder) {
        boolean same=Objects.equals(name.strip(),existing.get("refund_bank_name")) && Objects.equals(bin,existing.get("bank_bin"))
            && Objects.equals(account,existing.get("refund_bank_account_number")) && Objects.equals(holder.strip(),existing.get("refund_bank_account_name"));
        if(same)return; // A retry after approval/completion must not alter anything.
        if(!"PENDING".equals(existing.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Bank details can only change while refund is PENDING");
        store.jdbc().update("""
            UPDATE refund_requests SET refund_bank_name=?,bank_bin=?,refund_bank_account_number=?,
                refund_bank_account_name=?,updated_at=now() WHERE id=?
            """,name.strip(),bin,account,holder.strip(),id);
    }
    static RefundView row(Map<String,Object> r){
        return new RefundView(((Number)r.get("id")).longValue(),((Number)r.get("booking_id")).longValue(),(BigDecimal)r.get("amount"),r.get("status").toString(),
            (String)r.get("refund_bank_name"),(String)r.get("bank_bin"),(String)r.get("refund_bank_account_number"),(String)r.get("refund_bank_account_name"),
            (String)r.get("reason"),(String)r.get("admin_note"),(String)r.get("refund_proof_image_url"),
            ((java.sql.Timestamp)r.get("created_at")).toInstant(),((java.sql.Timestamp)r.get("updated_at")).toInstant(),(String)r.get("customer_phone"));
    }
}
