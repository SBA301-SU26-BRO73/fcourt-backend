package com.fcourt.voucher;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
class VoucherUsageRepository {
    private final NamedParameterJdbcTemplate jdbc;
    VoucherUsageRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Snapshot(String customerName, String customerPhone, String bookingCode) {
        static final Snapshot NONE = new Snapshot(null, null, null);
    }

    void record(VoucherRedemption application, long paymentId, Instant confirmedAt, Snapshot snapshot) {
        var params = new MapSqlParameterSource("id", application.applicationId()).addValue("voucher", application.voucherId())
                .addValue("booking", application.bookingId()).addValue("user", application.customerId()).addValue("branch", application.branchId())
                .addValue("discount", application.discountAmount()).addValue("payment", paymentId)
                .addValue("usedAt", confirmedAt == null ? null : Timestamp.from(confirmedAt))
                .addValue("source", confirmedAt == null ? "RECORDED_AT" : "PAYMENT_CONFIRMED")
                .addValue("name", snapshot.customerName()).addValue("phone", snapshot.customerPhone())
                .addValue("digits", VoucherPhone.storable(snapshot.customerPhone())).addValue("code", snapshot.bookingCode());
        jdbc.update("""
                INSERT INTO voucher_usages(id,voucher_id,booking_id,user_id,branch_id,discount_amount,used_at,used_at_source,payment_id,
                    customer_name,customer_phone,customer_phone_digits,booking_code)
                VALUES (:id,:voucher,:booking,:user,:branch,:discount,COALESCE(CAST(:usedAt AS timestamptz),now()),:source,:payment,
                    :name,:phone,:digits,:code)
                """, params);
    }

    /** Backfill: fills only NULL snapshot columns of the matching usage; returns rows changed (0 or 1). Never overwrites. */
    int fillSnapshot(UUID applicationId, long voucherId, long bookingId, long userId, long branchId, Snapshot snapshot) {
        var params = new MapSqlParameterSource("id", applicationId).addValue("voucher", voucherId).addValue("booking", bookingId)
                .addValue("user", userId).addValue("branch", branchId).addValue("name", snapshot.customerName())
                .addValue("phone", snapshot.customerPhone()).addValue("digits", VoucherPhone.storable(snapshot.customerPhone()))
                .addValue("code", snapshot.bookingCode());
        return jdbc.update("""
                UPDATE voucher_usages SET customer_name=COALESCE(customer_name,:name),customer_phone=COALESCE(customer_phone,:phone),
                    customer_phone_digits=COALESCE(customer_phone_digits,:digits),booking_code=COALESCE(booking_code,:code)
                WHERE id=:id AND voucher_id=:voucher AND booking_id=:booking AND user_id=:user AND branch_id=:branch
                  AND (customer_name IS NULL OR customer_phone IS NULL OR customer_phone_digits IS NULL OR booking_code IS NULL)
                """, params);
    }

    boolean visible(long voucherId, List<Long> branches) {
        if (branches.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vouchers WHERE id=:voucher AND branch_id IN (:branches))",
                new MapSqlParameterSource("voucher", voucherId).addValue("branches", branches), Boolean.class));
    }

    VoucherUsage.Page page(long voucherId, List<Long> branches, String phoneDigits, int page, int size) {
        if (branches.isEmpty()) return new VoucherUsage.Page(List.of(), page, size, 0, new BigDecimal("0.00"));
        var params = new MapSqlParameterSource("voucher", voucherId).addValue("branches", branches)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String where = " WHERE voucher_id=:voucher AND branch_id IN (:branches)";
        if (phoneDigits != null) {
            // digits-only prefix bound as a parameter; no LIKE metacharacters can occur
            where += " AND customer_phone_digits LIKE :phonePrefix";
            params.addValue("phonePrefix", phoneDigits + "%");
        }
        var totals = jdbc.queryForMap("SELECT count(*) AS total,COALESCE(sum(discount_amount),0) AS total_discount_amount FROM voucher_usages" + where, params);
        var items = jdbc.query("SELECT * FROM voucher_usages" + where + " ORDER BY used_at DESC,booking_id DESC LIMIT :limit OFFSET :offset", params,
                (row, index) -> new VoucherUsage(row.getObject("id", UUID.class), row.getLong("voucher_id"), row.getLong("booking_id"),
                        row.getLong("user_id"), row.getBigDecimal("discount_amount"), row.getTimestamp("used_at").toInstant(), row.getString("used_at_source"),
                        row.getString("customer_name"), row.getString("customer_phone"), row.getString("booking_code")));
        return new VoucherUsage.Page(items, page, size, ((Number) totals.get("total")).longValue(),
                ((BigDecimal) totals.get("total_discount_amount")).setScale(2));
    }
}
