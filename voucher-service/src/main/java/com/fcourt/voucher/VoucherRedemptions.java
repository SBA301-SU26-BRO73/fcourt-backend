package com.fcourt.voucher;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class VoucherRedemptions {
    final JdbcTemplate jdbc;
    VoucherRedemptions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<VoucherRedemption> booking(long id, boolean lock) {
        return jdbc.query("SELECT * FROM voucher_redemptions WHERE booking_id=?" + (lock ? " FOR UPDATE" : ""),
                this::row, id).stream().findFirst();
    }
    long reserved(long voucherId) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions WHERE voucher_id=? AND status='RESERVED'", Long.class, voucherId));
    }
    boolean alreadyUsed(long voucherId, long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM voucher_redemptions WHERE voucher_id=? AND customer_id=? AND status IN ('RESERVED','APPLIED'))",
                Boolean.class, voucherId, customerId));
    }
    void insert(VoucherRedemption application) {
        jdbc.update("""
                INSERT INTO voucher_redemptions(id,voucher_id,booking_id,customer_id,branch_id,original_amount,
                    discount_amount,final_amount,expires_at,status) VALUES (?,?,?,?,?,?,?,?,?,'RESERVED')
                """, application.applicationId(), application.voucherId(), application.bookingId(), application.customerId(),
                application.branchId(), application.originalAmount(), application.discountAmount(), application.finalAmount(),
                Timestamp.from(application.expiresAt()));
    }
    void release(long bookingId) {
        jdbc.update("UPDATE voucher_redemptions SET status='RELEASED',updated_at=now() WHERE booking_id=? AND status='RESERVED'", bookingId);
    }
    void lockVoucher(long voucherId) {
        jdbc.queryForList("SELECT id FROM vouchers WHERE id=? FOR UPDATE", voucherId);
    }
    List<VoucherRedemption> expired(java.time.Instant now) {
        return jdbc.query("SELECT * FROM voucher_redemptions WHERE status='RESERVED' AND expires_at<=? ORDER BY expires_at LIMIT 100",
                this::row, Timestamp.from(now));
    }
    private VoucherRedemption row(ResultSet row, int index) throws SQLException {
        return new VoucherRedemption(row.getObject("id", UUID.class), row.getLong("voucher_id"), row.getLong("booking_id"),
                row.getLong("customer_id"), row.getLong("branch_id"), row.getBigDecimal("original_amount"),
                row.getBigDecimal("discount_amount"), row.getBigDecimal("final_amount"), row.getTimestamp("expires_at").toInstant(),
                row.getString("status"), row.getObject("payment_id", Long.class));
    }
}
