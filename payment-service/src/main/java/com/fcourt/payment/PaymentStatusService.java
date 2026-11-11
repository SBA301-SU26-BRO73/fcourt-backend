package com.fcourt.payment;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class PaymentStatusService {
    private final ReceiptService authority;
    private final CustomerPayments identities;
    private final PaymentStore store;
    private final PaymentStatusCache cache;
    PaymentStatusService(ReceiptService authority,CustomerPayments identities,PaymentStore store,PaymentStatusCache cache){this.authority=authority;this.identities=identities;this.store=store;this.cache=cache;}
    PaymentStatusCache.Status status(long id,String auth,String hold){
        Long owner=identities.identity(auth);
        if(owner==null && (hold==null || hold.isBlank() || hold.length()>256))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Credentials required");
        String hash=hold==null?null:hash(hold);
        var cached=cache.get(id);
        if(cached!=null && cached.status()!=null && cached.status().bookingId()==id && cached.version()>=0
                && cached.status().updatedAt()!=null && Set.of("PENDING","SUCCESS","REJECTED").contains(cached.status().paymentStatus())
                && (cached.owner()!=null || cached.guestHash()!=null)
                && Objects.equals(owner,cached.owner()) && same(hash,cached.guestHash()))return cached.status();
        authority.authorize(id,auth,hold);
        var rows=store.jdbc().queryForList("SELECT status,rejection_reason,updated_at,submitted_at,status_version FROM payments WHERE booking_id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Receipt not submitted");
        var row=rows.getFirst();
        if("CANCELLED".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.GONE,"Checkout expired");
        if(row.get("submitted_at")==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Receipt not submitted");
        String state=switch(row.get("status").toString()){case "CONFIRMED"->"SUCCESS";case "PENDING"->"PENDING";default->"REJECTED";};
        var result=new PaymentStatusCache.Status(id,state,(String)row.get("rejection_reason"),((java.sql.Timestamp)row.get("updated_at")).toInstant());
        cache.put(new PaymentStatusCache.Entry(result,owner,hash,((Number)row.get("status_version")).longValue()));return result;
    }
    static boolean same(String a,String b){return a==null?b==null:b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
