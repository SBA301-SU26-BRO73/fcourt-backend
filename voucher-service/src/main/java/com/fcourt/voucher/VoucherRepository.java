package com.fcourt.voucher;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
class VoucherRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Voucher> mapper = this::row;
    VoucherRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Voucher> find(long id, List<Long> branches, boolean lock) {
        if (branches.isEmpty()) return Optional.empty();
        return jdbc.query("SELECT * FROM vouchers WHERE id=:id AND branch_id IN (:branches) AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : ""), new MapSqlParameterSource("id", id).addValue("branches", branches), mapper)
                .stream().findFirst();
    }

    Optional<Voucher> byCode(String code) {
        return jdbc.query("SELECT * FROM vouchers WHERE code=:code AND deleted_at IS NULL",
                new MapSqlParameterSource("code", code), mapper).stream().findFirst();
    }

    Voucher.Page page(List<Long> branches, int page, int size) {
        if (branches.isEmpty()) return new Voucher.Page(List.of(), page, size, 0);
        var params = new MapSqlParameterSource("branches", branches).addValue("limit", size)
                .addValue("offset", (long) page * size);
        String where = " WHERE branch_id IN (:branches) AND deleted_at IS NULL";
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM vouchers" + where, params, Long.class));
        return new Voucher.Page(jdbc.query("SELECT * FROM vouchers" + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, mapper), page, size, total);
    }

    Voucher create(VoucherRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO vouchers(code,branch_id,discount_type,discount_value,max_discount_amount,min_order_amount,
                    start_date,end_date,usage_limit,status)
                VALUES (:code,:branch,:type,:value,:cap,:minimum,:start,:end,:limit,:status) RETURNING *
                """, params(request), mapper);
    }

    Voucher update(long id, VoucherRequest request) {
        return jdbc.queryForObject("""
                UPDATE vouchers SET code=:code,branch_id=:branch,discount_type=:type,discount_value=:value,
                    max_discount_amount=:cap,min_order_amount=:minimum,start_date=:start,end_date=:end,
                    usage_limit=:limit,status=:status,updated_at=now()
                WHERE id=:id AND deleted_at IS NULL RETURNING *
                """, params(request).addValue("id", id), mapper);
    }

    void delete(long id) {
        jdbc.update("UPDATE vouchers SET deleted_at=now(),updated_at=now(),status='EXPIRED' WHERE id=:id",
                new MapSqlParameterSource("id", id));
    }

    private MapSqlParameterSource params(VoucherRequest request) {
        return new MapSqlParameterSource("code", request.code()).addValue("branch", request.branchId())
                .addValue("type", request.discountType().name()).addValue("value", request.discountValue())
                .addValue("cap", request.maxDiscountAmount()).addValue("minimum", request.minOrderAmount())
                .addValue("start", Timestamp.from(request.startDate())).addValue("end", Timestamp.from(request.endDate()))
                .addValue("limit", request.usageLimit()).addValue("status", request.status().name());
    }

    private Voucher row(ResultSet row, int index) throws SQLException {
        return new Voucher(row.getLong("id"), row.getString("code"), row.getLong("branch_id"),
                Voucher.DiscountType.valueOf(row.getString("discount_type")), row.getBigDecimal("discount_value"),
                row.getBigDecimal("max_discount_amount"), row.getBigDecimal("min_order_amount"),
                row.getTimestamp("start_date").toInstant(), row.getTimestamp("end_date").toInstant(),
                row.getInt("usage_limit"), row.getInt("used_count"), Voucher.Status.valueOf(row.getString("status")));
    }
}
