package com.fcourt.booking;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@Service
class BookingQueries {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    BookingQueries(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    static long actor(Jwt jwt) {
        if (jwt==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        try { long id=Long.parseLong(jwt.getSubject()); if(id<=0) throw new NumberFormatException(); return id; }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid user identity"); }
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    BookingView.Page history(Jwt jwt,String filter,int page,int size) {
        long user=actor(jwt);
        if(page<0 || page>100000 || size<1 || size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        String condition=switch(filter) {
            case "ALL" -> "";
            case "UPCOMING" -> " AND b.status IN ('PENDING_PAYMENT','AWAITING_CONFIRMATION','CONFIRMED','CHECKED_IN') AND (b.date>? OR (b.date=? AND COALESCE((SELECT max(s.slot_end) FROM booking_slots s WHERE s.booking_id=b.id),'23:59:59'::time)>?))";
            case "COMPLETED" -> " AND b.status='COMPLETED'";
            case "CANCELLED" -> " AND b.status='CANCELLED'";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid booking filter");
        };
        var args=new ArrayList<Object>(); args.add(user);
        if(filter.equals("UPCOMING")) {
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Ho_Chi_Minh"));
            args.add(now.toLocalDate());args.add(now.toLocalDate());args.add(now.toLocalTime());
        }
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM bookings b WHERE b.customer_id=?"+condition,Long.class,args.toArray()));
        args.add(size);args.add((long)page*size);
        var items=withSlots(jdbc.query("SELECT b.* FROM bookings b WHERE b.customer_id=?"+condition+" ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?",this::row,args.toArray()));
        return new BookingView.Page(items,page,size,total);
    }
    @Transactional(readOnly=true)
    BookingView detail(long id,Jwt jwt,String holdToken) {
        if(jwt==null && (holdToken==null || holdToken.isBlank() || holdToken.length()>256))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        var rows=jdbc.queryForList("SELECT customer_id,hold_token_hash FROM bookings WHERE id=?",id);
        if(rows.isEmpty()) throw missing();
        var row=rows.getFirst();
        boolean owner=jwt!=null && row.get("customer_id") instanceof Number n && n.longValue()==actor(jwt);
        boolean guest=row.get("customer_id")==null && holdToken!=null && row.get("hold_token_hash") instanceof String hash
            && MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),hash(holdToken).getBytes(StandardCharsets.US_ASCII));
        if(!owner && !guest) throw missing();
        return withSlots(jdbc.query("SELECT * FROM bookings WHERE id=?",this::row,id)).getFirst();
    }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Booking not found"); }
    private List<BookingView> withSlots(List<BookingView> bookings) {
        if(bookings.isEmpty()) return bookings;
        var slots=new HashMap<Long,List<BookingView.Slot>>();
        jdbc.query("SELECT booking_id,slot_start,slot_end,price_at_booking FROM booking_slots WHERE booking_id=ANY(?) ORDER BY booking_id,slot_start,id",
            rs -> { slots.computeIfAbsent(rs.getLong(1),k -> new ArrayList<>())
                .add(new BookingView.Slot(rs.getObject(2,LocalTime.class),rs.getObject(3,LocalTime.class),rs.getBigDecimal(4))); },
            (Object) bookings.stream().map(BookingView::id).toArray(Long[]::new));
        return bookings.stream().map(b -> b.withSlots(slots.getOrDefault(b.id(),List.of()))).toList();
    }
    private BookingView row(ResultSet r,int index) throws SQLException {
        long id=r.getLong("id");
        var expiry=r.getTimestamp("hold_expires_at");
        return new BookingView(id,r.getString("booking_code"),r.getObject("branch_id",Long.class),r.getString("branch_name"),
            r.getString("branch_address"),r.getString("branch_phone"),r.getString("court_name"),r.getObject("date",LocalDate.class),List.of(),
            r.getBigDecimal("total_price"),r.getString("status"),r.getString("customer_name"),expiry==null?null:expiry.toInstant(),
            r.getString("checkin_code"),r.getString("qr_image_url"),r.getString("receipt_image_url"),r.getString("payment_status"),r.getTimestamp("created_at").toInstant(),r.getBigDecimal("refund_amount"),r.getString("cancellation_reason"),r.getLong("court_id"));
    }
}
