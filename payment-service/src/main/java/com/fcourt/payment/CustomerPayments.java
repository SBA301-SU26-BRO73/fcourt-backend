package com.fcourt.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
class CustomerPayments {
    private final JdbcTemplate jdbc;
    private final JwtDecoder decoder;
    private final RestClient booking;
    private final TransactionTemplate tx;
    CustomerPayments(JdbcTemplate jdbc,JwtDecoder decoder,@Qualifier("bookingClient") RestClient booking,TransactionTemplate tx){this.jdbc=jdbc;this.decoder=decoder;this.booking=booking;this.tx=tx;}
    static long actor(Jwt jwt){
        try{long id=Long.parseLong(jwt.getSubject());if(id<=0)throw new NumberFormatException();return id;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid customer identity");}
    }
    Long identity(String auth){
        if(auth==null)return null;
        if(!auth.startsWith("Bearer ") || auth.length()<=7)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        try{return actor(decoder.decode(auth.substring(7)));}
        catch(JwtException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");}
    }
    // Called only after the booking authority accepts the very same Bearer credential.
    void claim(long bookingId,Long customer){
        if(customer==null)return;
        if(jdbc.update("UPDATE payments SET customer_id=? WHERE booking_id=? AND (customer_id IS NULL OR customer_id=?)",customer,bookingId,customer)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment ownership mismatch");
    }
    record Transaction(long id,String transactionCode,long bookingId,String bookingCode,String branchName,String courtName,
            BigDecimal amount,String method,String status,Instant occurredAt,String receiptImageUrl,String customerName,String customerPhone) {}
    record Page(List<Transaction> items,int page,int size,long total) {}
    Page history(Jwt jwt,int page,int size){
        long customer=actor(jwt);
        if(page<0 || page>100000 || size<1 || size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        reconcile(jwt,customer);
        return tx.execute(s->{
            // A single SQL statement gives count/items a consistent snapshot, including empty pages.
            var rows=jdbc.queryForList("""
                WITH filtered AS (SELECT * FROM payments WHERE customer_id=? AND submitted_at IS NOT NULL),
                  counts AS (SELECT count(*) AS total FROM filtered),
                  items AS (SELECT * FROM filtered ORDER BY submitted_at DESC,id DESC LIMIT ? OFFSET ?)
                SELECT counts.total,items.* FROM counts LEFT JOIN items ON true ORDER BY items.submitted_at DESC,items.id DESC
                """,customer,size,(long)page*size);
            long total=((Number)rows.getFirst().get("total")).longValue();
            var items=rows.stream().filter(r->r.get("id")!=null).map(this::row).toList();
            return new Page(items,page,size,total);
        });
    }
    Transaction row(Map<String,Object> r){
        long id=((Number)r.get("id")).longValue();
        JsonNode checkout;
        try{checkout=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.get("checkout").toString());}
        catch(Exception e){throw new IllegalStateException("Invalid stored checkout",e);}
        return new Transaction(id,"PAY-"+id,((Number)r.get("booking_id")).longValue(),checkout.path("bookingCode").asText(),
            checkout.path("branchName").asText(),checkout.path("courtName").asText(),(BigDecimal)r.get("amount"),"VIETQR",
            switch(r.get("status").toString()){case "CONFIRMED"->"SUCCESS";case "PENDING"->"PENDING";default->"REJECTED";},
            ((java.sql.Timestamp)r.get("submitted_at")).toInstant(),(String)r.get("bill_image_url"),checkout.path("customerName").asText(),(String)r.get("customer_phone"));
    }
    void reconcile(Jwt jwt,long customer){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_customer_sync WHERE customer_id=?)",Boolean.class,customer)))return;
        // Legacy receipts have no verified customer snapshot. Resolve only through owner-scoped booking history.
        var ids=new HashSet<Long>();long total=-1;
        try{
            for(int page=0;page<10000;page++){
                final int index=page;
                var data=booking.get().uri(b->b.path("/api/bookings/my").queryParam("status","ALL").queryParam("page",index).queryParam("size",100).build())
                    .header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().body(JsonNode.class);
                if(data==null || data.path("page").asInt(-1)!=page || data.path("size").asInt()!=100 || !data.path("items").isArray() || !data.path("total").canConvertToLong())throw PaymentUpstreams.unavailable();
                long count=data.path("total").asLong();
                if(count<0 || count>1000000 || (total!=-1 && total!=count))throw PaymentUpstreams.unavailable();
                total=count;
                for(var item:data.path("items")){
                    long id=item.path("id").asLong();if(id<=0 || !ids.add(id))throw PaymentUpstreams.unavailable();
                }
                if(ids.size()==total)break;
                if(data.path("items").size()!=100)throw PaymentUpstreams.unavailable();
            }
            if(ids.size()!=total)throw PaymentUpstreams.unavailable();
        }catch(org.springframework.web.client.RestClientException e){throw PaymentUpstreams.unavailable();}
        tx.executeWithoutResult(s->{
            for(long id:ids)jdbc.update("UPDATE payments SET customer_id=? WHERE booking_id=? AND customer_id IS NULL",customer,id);
            jdbc.update("INSERT INTO payment_customer_sync(customer_id) VALUES (?) ON CONFLICT DO NOTHING",customer);
        });
    }
}
