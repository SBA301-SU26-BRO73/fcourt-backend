package com.fcourt.payment;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class AdminRefundService {
    private final PaymentStore store;
    private final BranchOwnership ownership;
    private final ReceiptStorage storage;
    private final TransactionTemplate tx;
    AdminRefundService(PaymentStore store,BranchOwnership ownership,ReceiptStorage storage,TransactionTemplate tx){this.store=store;this.ownership=ownership;this.storage=storage;this.tx=tx;}
    List<RefundView> pending(long branch,int page,int size,Jwt jwt){
        if(branch<=0||page<0||page>100000||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        ownership.require(branch,jwt);
        return store.jdbc().queryForList("SELECT * FROM refund_requests WHERE branch_id=? AND status IN ('PENDING','APPROVED') ORDER BY created_at,id LIMIT ? OFFSET ?",branch,size,(long)page*size).stream().map(RefundService::row).toList();
    }
    private Map<String,Object> authorized(long id,Jwt jwt){
        var rows=store.jdbc().queryForList("SELECT * FROM refund_requests WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Refund not found");
        var row=rows.getFirst();
        if(!(row.get("branch_id") instanceof Number branch))throw new ResponseStatusException(HttpStatus.CONFLICT,"Legacy refund requires branch reconciliation");
        ownership.require(branch.longValue(),jwt);return row;
    }
    RefundView decide(long id,String action,String note,MultipartFile proof,Jwt jwt){
        var first=authorized(id,jwt);String initial=first.get("status").toString();
        if(!Set.of("APPROVED","REJECTED","COMPLETED").contains(action))throw new IllegalArgumentException("Invalid refund decision");
        if(initial.equals(action))return RefundService.row(first);
        if(Set.of("COMPLETED","REJECTED").contains(initial))throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund decision already final");
        if(action.equals("REJECTED")&&(note==null||note.isBlank()||note.strip().length()>500))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rejection reason required (1-500 characters)");
        if(action.equals("COMPLETED") && proof==null)throw ReceiptValidator.invalid();
        String proofUrl=action.equals("COMPLETED")?storage.upload(ReceiptValidator.read(proof)):null;
        if(proofUrl!=null){
            var uri=java.net.URI.create(proofUrl);
            if(!"https".equals(uri.getScheme())||!"res.cloudinary.com".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid proof storage response");
        }
        long booking=((Number)first.get("booking_id")).longValue();
        return tx.execute(s->{
            store.jdbc().queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,booking);
            var row=store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE id=? FOR UPDATE",id);
            String state=row.get("status").toString();
            if(state.equals(action))return RefundService.row(row);
            if(!Set.of("PENDING","APPROVED").contains(state))throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund decision already final");
            var payment=store.find(booking);
            if(payment==null||!"CONFIRMED".equals(payment.status())||((java.math.BigDecimal)row.get("amount")).compareTo(payment.amount())>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirmed payment no longer matches refund");
            store.jdbc().update("""
              UPDATE refund_requests SET status=?,admin_note=?,refund_proof_image_url=COALESCE(?,refund_proof_image_url),
                approved_at=CASE WHEN ?='APPROVED' THEN COALESCE(approved_at,now()) ELSE approved_at END,
                confirmed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE confirmed_at END,confirmed_by=?,updated_at=now() WHERE id=?
              """,action,note==null?null:note.strip(),proofUrl,action,action,ownership.actor(jwt),id);
            var result=RefundService.row(store.jdbc().queryForMap("SELECT * FROM refund_requests WHERE id=?",id));
            if(action.equals("COMPLETED")){
                UUID eventId=UUID.randomUUID();
                store.jdbc().update("INSERT INTO payment_outbox(event_id,booking_id,event_type,payload) VALUES (?,?,?,?::jsonb)",eventId,booking,"RefundCompletedEvent",
                    store.encode(Map.of("eventId",eventId,"type","RefundCompletedEvent","refund",Map.of("id",id,"bookingId",booking,"branchId",row.get("branch_id"),"customerId",row.get("customer_id"),"amount",result.amount(),"status",result.status(),"transferProofUrl",result.transferProofUrl()))));
            }
            return result;
        });
    }
}
