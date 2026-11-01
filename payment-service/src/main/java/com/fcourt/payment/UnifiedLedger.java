package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class UnifiedLedger {
    private final PaymentStore store;private final BranchOwnership ownership;
    UnifiedLedger(PaymentStore store,BranchOwnership ownership){this.store=store;this.ownership=ownership;}
    record Entry(long id,String transactionKey,String transactionType,String transactionCode,long bookingId,String bookingCode,
        String branchName,String courtName,BigDecimal amount,String method,String status,Instant occurredAt,String receiptImageUrl) {}
    record Summary(BigDecimal received,BigDecimal refunded,BigDecimal netRevenue,BigDecimal pending,long rejectedCount) {}
    record Page(List<Entry> items,int page,int size,long total,Summary summary) {}
    Page history(long branch,LocalDate from,LocalDate to,String state,String search,String type,int page,int size,Jwt jwt){
        if(branch<=0||page<0||page>100000||size<1||size>100||search.length()>100||(from!=null&&to!=null&&from.isAfter(to))
            ||!Set.of("ALL","BOOKING_PAYMENT","REFUND","WALK_IN_CASH").contains(type)||!Set.of("ALL","SUCCESS","PENDING","REJECTED").contains(state))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid ledger filters");
        ownership.require(branch,jwt);
        var args=new ArrayList<Object>();args.add(branch);var where=new StringBuilder(" WHERE branch_id=?");
        if(from!=null){where.append(" AND occurred_at>=?");args.add(java.sql.Timestamp.from(from.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        if(to!=null){where.append(" AND occurred_at<?");args.add(java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        if(!state.equals("ALL")){where.append(" AND status=?");args.add(state);}
        if(!type.equals("ALL")){where.append(" AND transaction_type=?");args.add(type);}
        if(!search.isBlank()){
            where.append(" AND (booking_code ILIKE ? OR transaction_code ILIKE ? OR customer_name ILIKE ? OR customer_phone ILIKE ?)");
            String value="%"+search.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";for(int i=0;i<4;i++)args.add(value);
        }
        args.add(size);args.add((long)page*size);
        var rows=store.jdbc().queryForList("""
          WITH flows AS (
            SELECT p.id,'P:'||p.id AS transaction_key,'BOOKING_PAYMENT'::text AS transaction_type,'PAY-'||p.id AS transaction_code,
              p.booking_id,p.branch_id,p.checkout->>'bookingCode' AS booking_code,p.checkout->>'branchName' AS branch_name,
              p.checkout->>'courtName' AS court_name,p.checkout->>'customerName' AS customer_name,p.customer_phone,
              p.amount,'VIETQR'::text AS method,CASE p.status WHEN 'CONFIRMED' THEN 'SUCCESS' WHEN 'PENDING' THEN 'PENDING' ELSE 'REJECTED' END AS status,
              COALESCE(p.confirmed_at,p.submitted_at) AS occurred_at,p.bill_image_url AS receipt_image_url
              FROM payments p WHERE p.submitted_at IS NOT NULL
            UNION ALL
            SELECT r.id,'R:'||r.id,'REFUND','RF-'||r.id,r.booking_id,r.branch_id,COALESCE(p.checkout->>'bookingCode','BK-'||r.booking_id),
              COALESCE(p.checkout->>'branchName','Chi nhánh #'||r.branch_id),COALESCE(p.checkout->>'courtName','Sân đã đặt'),
              r.refund_bank_account_name,NULL,-r.amount,'REFUND_TRANSFER','SUCCESS',r.confirmed_at,r.refund_proof_image_url
              FROM refund_requests r LEFT JOIN payments p ON p.booking_id=r.booking_id WHERE r.status='COMPLETED' AND r.confirmed_at IS NOT NULL
            UNION ALL
            SELECT c.id,'C:'||c.id,'WALK_IN_CASH',c.receipt_code,c.booking_id,c.branch_id,c.booking_code,
              'Chi nhánh #'||c.branch_id,'Sân #'||c.court_id,NULL,NULL,c.amount,'CASH','SUCCESS',c.occurred_at,NULL FROM walk_in_cash_ledger c
          ), filtered AS (SELECT * FROM flows
          """+where+"""
          ), totals AS (SELECT count(*) AS total,
            COALESCE(sum(amount) FILTER(WHERE status='SUCCESS' AND amount>=0),0) AS received,
            COALESCE(-sum(amount) FILTER(WHERE status='SUCCESS' AND amount<0),0) AS refunded,
            COALESCE(sum(amount) FILTER(WHERE status='SUCCESS'),0) AS net_revenue,
            COALESCE(sum(amount) FILTER(WHERE status='PENDING'),0) AS pending,
            count(*) FILTER(WHERE status='REJECTED') AS rejected_count FROM filtered),
          items AS (SELECT * FROM filtered ORDER BY occurred_at DESC,transaction_key DESC LIMIT ? OFFSET ?)
          SELECT totals.*,items.* FROM totals LEFT JOIN items ON true ORDER BY items.occurred_at DESC,items.transaction_key DESC
          """,args.toArray());
        var r=rows.getFirst();
        return new Page(rows.stream().filter(v->v.get("id")!=null).map(this::row).toList(),page,size,((Number)r.get("total")).longValue(),
            new Summary((BigDecimal)r.get("received"),(BigDecimal)r.get("refunded"),(BigDecimal)r.get("net_revenue"),(BigDecimal)r.get("pending"),((Number)r.get("rejected_count")).longValue()));
    }
    private Entry row(Map<String,Object> r){return new Entry(((Number)r.get("id")).longValue(),(String)r.get("transaction_key"),(String)r.get("transaction_type"),(String)r.get("transaction_code"),
        ((Number)r.get("booking_id")).longValue(),(String)r.get("booking_code"),(String)r.get("branch_name"),(String)r.get("court_name"),(BigDecimal)r.get("amount"),(String)r.get("method"),
        (String)r.get("status"),((java.sql.Timestamp)r.get("occurred_at")).toInstant(),(String)r.get("receipt_image_url"));}
}
