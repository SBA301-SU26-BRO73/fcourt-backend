package com.fcourt.booking;

import java.math.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class BookingCancellation {
    @org.springframework.beans.factory.annotation.Autowired(required=false) SlotChanges changes;
    private final JdbcTemplate jdbc;
    private final BookingQueries queries;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration cutoff;
    private final BigDecimal refundPercent;
    BookingCancellation(JdbcTemplate jdbc,BookingQueries queries,ObjectMapper json,Clock clock,
            @Value("${booking.cancel-cutoff-hours:12}") int hours,@Value("${booking.refund-percent:100}") BigDecimal percent) {
        if(hours<0 || percent.signum()<0 || percent.compareTo(BigDecimal.valueOf(100))>0) throw new IllegalArgumentException("Invalid cancellation policy");
        this.jdbc=jdbc;this.queries=queries;this.json=json;this.clock=clock;cutoff=Duration.ofHours(hours);refundPercent=percent;
    }
    record Policy(boolean allowed,String reason,Instant startsAt,Instant cancelBefore,long cutoffHours,
            BigDecimal refundAmount,BigDecimal refundPercent,String currency) {}
    @Transactional(readOnly=true)
    Policy quote(long id,Jwt jwt) {
        var b=owned(id,jwt,false);return policy(b);
    }
    private Map<String,Object> owned(long id,Jwt jwt,boolean lock) {
        long user=BookingQueries.actor(jwt);
        var rows=jdbc.queryForList("SELECT * FROM bookings WHERE id=? AND customer_id=?"+(lock?" FOR UPDATE":""),id,user);
        if(rows.isEmpty()) throw BookingQueries.missing();return rows.getFirst();
    }
    private Policy policy(Map<String,Object> b) {
        long id=((Number)b.get("id")).longValue();
        var start=jdbc.queryForObject("SELECT min(booking_date+slot_start) FROM booking_slots WHERE booking_id=?",LocalDateTime.class,id);
        Instant starts=start==null?null:start.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        Instant before=starts==null?null:starts.minus(cutoff);
        String status=b.get("status").toString();
        boolean valid=Set.of("PENDING_PAYMENT","AWAITING_CONFIRMATION","CONFIRMED").contains(status);
        boolean allowed=valid && before!=null && !clock.instant().isAfter(before);
        BigDecimal amount=allowed && "CONFIRMED".equals(b.get("payment_status"))
            ? ((BigDecimal)b.get("total_price")).multiply(refundPercent).divide(BigDecimal.valueOf(100),0,RoundingMode.DOWN):BigDecimal.ZERO;
        return new Policy(allowed,!valid?"Trạng thái đơn không cho phép hủy":starts==null?"Chưa có lịch sân để xác định chính sách":allowed?"Được hủy trước hạn quy định":"Đã quá thời hạn hủy",starts,before,cutoff.toHours(),amount,refundPercent,"VND");
    }
    @Transactional
    BookingView cancel(long id,String reason,Jwt jwt) {
        if(reason==null || reason.isBlank() || reason.strip().length()>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cancellation reason required (1-500 characters)");
        var b=owned(id,jwt,true);
        if(b.get("status").equals("CANCELLED"))return queries.detail(id,jwt,null);
        var policy=policy(b);
        if(!policy.allowed())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,policy.reason());
        var slots=jdbc.queryForList("SELECT court_id AS \"courtId\",booking_date AS date,slot_start AS \"startTime\",slot_end AS \"endTime\" FROM booking_slots WHERE booking_id=? ORDER BY slot_start",id);
        jdbc.update("UPDATE bookings SET status='CANCELLED',cancellation_reason=?,cancelled_at=?,refund_amount=?,updated_at=now() WHERE id=?",reason.strip(),java.sql.Timestamp.from(clock.instant()),policy.refundAmount(),id);
        jdbc.update("UPDATE booking_slots SET active=false WHERE booking_id=?",id);
        UUID eventId=UUID.randomUUID();
        var booking=new LinkedHashMap<String,Object>();
        booking.put("id",id);booking.put("bookingId",id);booking.put("branchId",b.get("branch_id"));booking.put("customerId",b.get("customer_id"));
        booking.put("bookingCode",b.get("booking_code"));booking.put("status","CANCELLED");booking.put("reason",reason.strip());
        booking.put("paymentId",b.get("payment_id"));booking.put("paymentStatus",b.get("payment_status"));booking.put("refundAmount",policy.refundAmount());booking.put("slots",slots);
        try {
            jdbc.update("INSERT INTO booking_outbox(event_id,booking_id,payload) VALUES (?,?,?::jsonb)",eventId,id,
                json.writeValueAsString(Map.of("eventId",eventId,"type","BookingCancelledEvent","booking",booking)));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        if(changes!=null) changes.afterCommit(id);
        return queries.detail(id,jwt,null);
    }
}
