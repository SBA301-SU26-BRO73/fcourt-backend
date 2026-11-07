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

    void record(VoucherRedemption application, long paymentId, Instant confirmedAt) {
        var params = new MapSqlParameterSource("id", application.applicationId()).addValue("voucher", application.voucherId())
                .addValue("booking", application.bookingId()).addValue("user", application.customerId()).addValue("branch", application.branchId())
                .addValue("discount", application.discountAmount()).addValue("payment", paymentId)
                .addValue("usedAt", confirmedAt == null ? null : Timestamp.from(confirmedAt))
                .addValue("source", confirmedAt == null ? "RECORDED_AT" : "PAYMENT_CONFIRMED");
        jdbc.update("""
                INSERT INTO voucher_usages(id,voucher_id,booking_id,user_id,branch_id,discount_amount,used_at,used_at_source,payment_id)
                VALUES (:id,:voucher,:booking,:user,:branch,:discount,COALESCE(CAST(:usedAt AS timestamptz),now()),:source,:payment)
                """, params);
    }

    boolean visible(long voucherId, List<Long> branches) {
        if (branches.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vouchers WHERE id=:voucher AND branch_id IN (:branches))",
                new MapSqlParameterSource("voucher", voucherId).addValue("branches", branches), Boolean.class));
    }

    VoucherUsage.Page page(long voucherId, List<Long> branches, int page, int size) {
        if (branches.isEmpty()) return new VoucherUsage.Page(List.of(), page, size, 0, new BigDecimal("0.00"));
        var params = new MapSqlParameterSource("voucher", voucherId).addValue("branches", branches)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String where = " WHERE voucher_id=:voucher AND branch_id IN (:branches)";
        var totals = jdbc.queryForMap("SELECT count(*) AS total,COALESCE(sum(discount_amount),0) AS total_discount_amount FROM voucher_usages" + where, params);
        var items = jdbc.query("SELECT * FROM voucher_usages" + where + " ORDER BY used_at DESC,booking_id DESC LIMIT :limit OFFSET :offset", params,
                (row, index) -> new VoucherUsage(row.getObject("id", UUID.class), row.getLong("voucher_id"), row.getLong("booking_id"),
                        row.getLong("user_id"), row.getBigDecimal("discount_amount"), row.getTimestamp("used_at").toInstant(), row.getString("used_at_source")));
        return new VoucherUsage.Page(items, page, size, ((Number) totals.get("total")).longValue(),
                ((BigDecimal) totals.get("total_discount_amount")).setScale(2));
    }
}
