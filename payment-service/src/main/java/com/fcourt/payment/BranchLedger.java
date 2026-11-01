package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class BranchLedger {
    private final PaymentStore store;
    private final CustomerPayments mapping;
    private final BranchOwnership ownership;
    BranchLedger(PaymentStore store,CustomerPayments mapping,BranchOwnership ownership){this.store=store;this.mapping=mapping;this.ownership=ownership;}
    record Summary(BigDecimal received,BigDecimal pending,long rejectedCount) {}
    record Page(List<CustomerPayments.Transaction> items,int page,int size,long total,Summary summary) {}
    Page history(long branch,LocalDate from,LocalDate to,String status,String search,int page,int size,Jwt jwt){
        if(branch<=0 || page<0 || page>100000 || size<1 || size>100 || (from!=null&&to!=null&&from.isAfter(to)) || search.length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid ledger filters");
        ownership.require(branch,jwt);
        var args=new ArrayList<Object>();args.add(branch);
        var where=new StringBuilder(" WHERE p.branch_id=? AND p.submitted_at IS NOT NULL");
        if(from!=null){where.append(" AND p.submitted_at>=?");args.add(java.sql.Timestamp.from(from.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        if(to!=null){where.append(" AND p.submitted_at<?");args.add(java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        if(!status.equals("ALL")){
            String state=switch(status){case "SUCCESS"->"CONFIRMED";case "PENDING"->"PENDING";case "REJECTED"->"REJECTED";default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid transaction status");};
            where.append(" AND p.status=?");args.add(state);
        }
        if(!search.isBlank()){
            String pattern="%"+search.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            where.append(" AND (p.checkout->>'customerName' ILIKE ? OR p.customer_phone ILIKE ? OR p.checkout->>'bookingCode' ILIKE ? OR ('PAY-'||p.id) ILIKE ?)");
            for(int i=0;i<4;i++)args.add(pattern);
        }
        args.add(size);args.add((long)page*size);
        var rows=store.jdbc().queryForList("""
          WITH filtered AS (SELECT p.* FROM payments p
          """+where+"""
          ), totals AS (SELECT count(*) AS total,
             COALESCE(sum(amount) FILTER(WHERE status='CONFIRMED'),0) AS received,
             COALESCE(sum(amount) FILTER(WHERE status='PENDING'),0) AS pending,
             count(*) FILTER(WHERE status='REJECTED') AS rejected_count FROM filtered),
          items AS (SELECT * FROM filtered ORDER BY submitted_at DESC,id DESC LIMIT ? OFFSET ?)
          SELECT totals.*,items.* FROM totals LEFT JOIN items ON true ORDER BY items.submitted_at DESC,items.id DESC
          """,args.toArray());
        var totals=rows.getFirst();
        return new Page(rows.stream().filter(r->r.get("id")!=null).map(mapping::row).toList(),page,size,
            ((Number)totals.get("total")).longValue(),new Summary((BigDecimal)totals.get("received"),(BigDecimal)totals.get("pending"),((Number)totals.get("rejected_count")).longValue()));
    }
}
