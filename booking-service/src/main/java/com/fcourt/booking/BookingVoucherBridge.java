package com.fcourt.booking;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class BookingVoucherBridge {
    private final JdbcTemplate jdbc;
    private final BookingQueries queries;
    private final Clock clock;
    private final String token;
    BookingVoucherBridge(JdbcTemplate jdbc, BookingQueries queries, Clock clock,
            @Value("${booking.integration-token:${BOOKING_INTEGRATION_TOKEN:}}") String token) {
        this.jdbc = jdbc; this.queries = queries; this.clock = clock; this.token = token;
    }

    void authenticate(String supplied) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Booking integration unavailable");
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(long id) { return snapshot(row(id, false)); }

    @Transactional
    public Snapshot attach(long id, Jwt jwt, Attachment attachment) {
        long customer = BookingQueries.actor(jwt);
        var row = row(id, true);
        if (!(row.get("customer_id") instanceof Number owner) || owner.longValue() != customer) throw BookingQueries.missing();
        if (attachment.applicationId().equals(row.get("voucher_application_id"))) {
            if (!Long.valueOf(attachment.voucherId()).equals(row.get("voucher_id"))
                    || !same(attachment.originalAmount(), row.get("original_amount"))
                    || !same(attachment.discountAmount(), row.get("discount_amount"))
                    || !same(attachment.finalAmount(), row.get("total_price"))) throw conflict();
            return snapshot(row);
        }
        requirePending(row);
        if (row.get("voucher_application_id") != null || Boolean.TRUE.equals(row.get("payment_frozen"))) throw conflict();
        if (!same(attachment.originalAmount(), row.get("total_price"))
                || attachment.originalAmount().subtract(attachment.discountAmount()).compareTo(attachment.finalAmount()) != 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số tiền áp dụng voucher không hợp lệ");
        jdbc.update("""
                UPDATE bookings SET voucher_application_id=?,voucher_id=?,original_amount=?,discount_amount=?,
                    total_price=?,updated_at=now() WHERE id=?
                """, attachment.applicationId(), attachment.voucherId(), attachment.originalAmount(),
                attachment.discountAmount(), attachment.finalAmount(), id);
        return snapshot(row(id, false));
    }

    @Transactional
    public Snapshot freeze(long id, Jwt jwt, String holdToken, BigDecimal expectedAmount) {
        queries.detail(id, jwt, holdToken);
        var row = row(id, true);
        requirePending(row);
        if (!same(expectedAmount, row.get("total_price"))) throw conflict();
        jdbc.update("UPDATE bookings SET payment_frozen=true,updated_at=now() WHERE id=?", id);
        return snapshot(row(id, false));
    }

    private void requirePending(Map<String, Object> row) {
        if (!"PENDING_PAYMENT".equals(row.get("status"))) throw conflict();
        if (!(row.get("hold_expires_at") instanceof java.sql.Timestamp expiry) || !expiry.toInstant().isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.GONE, "Đơn đặt sân đã hết thời gian giữ chỗ");
    }
    private Map<String, Object> row(long id, boolean lock) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = jdbc.queryForList("SELECT * FROM bookings WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw BookingQueries.missing();
        return rows.getFirst();
    }
    private boolean same(BigDecimal amount, Object stored) { return stored instanceof BigDecimal value && amount.compareTo(value) == 0; }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Giá đơn đã khóa hoặc không còn có thể áp dụng voucher"); }
    private Snapshot snapshot(Map<String, Object> row) {
        var expiry = (java.sql.Timestamp) row.get("hold_expires_at");
        return new Snapshot(((Number) row.get("id")).longValue(), (Long) row.get("customer_id"), (Long) row.get("branch_id"),
                row.get("status").toString(), expiry == null ? null : expiry.toInstant(),
                (UUID) row.get("voucher_application_id"), (Long) row.get("voucher_id"),
                (BigDecimal) row.get("original_amount"), (BigDecimal) row.get("discount_amount"),
                (BigDecimal) row.get("total_price"), Boolean.TRUE.equals(row.get("payment_frozen")));
    }
    record Snapshot(long id, Long customerId, Long branchId, String status, Instant holdExpiresAt,
            UUID voucherApplicationId, Long voucherId, BigDecimal originalAmount, BigDecimal discountAmount,
            BigDecimal totalPrice, boolean paymentFrozen) {}
    record Attachment(@NotNull UUID applicationId, @Positive long voucherId,
            @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal originalAmount,
            @NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal discountAmount,
            @NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal finalAmount) {}
    record Freeze(@NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal amount) {}
}
