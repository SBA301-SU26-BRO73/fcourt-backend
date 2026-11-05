package com.fcourt.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AdminBookingQueries {
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminBranchAuthority authority;

    AdminBookingQueries(NamedParameterJdbcTemplate jdbc, AdminBranchAuthority authority) {
        this.jdbc = jdbc;
        this.authority = authority;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminBookingView.Page search(Jwt jwt, AdminBookingFilter filter) {
        List<Long> branches = authority.ownedBranches(jwt);
        if (filter.branchId() != null && !branches.contains(filter.branchId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch ownership required");
        if (branches.isEmpty()) return new AdminBookingView.Page(List.of(), filter.pageNumber(), filter.pageSize(), 0);
        var params = new MapSqlParameterSource("branches", branches);
        var where = new StringBuilder(" WHERE b.branch_id IN (:branches)");
        if (filter.branchId() != null) {
            where.append(" AND b.branch_id = :branch");
            params.addValue("branch", filter.branchId());
        }
        if (filter.courtId() != null) {
            where.append(" AND b.court_id = :court");
            params.addValue("court", filter.courtId());
        }
        if (filter.fromDate() != null) {
            where.append(" AND b.date >= :fromDate");
            params.addValue("fromDate", java.sql.Date.valueOf(filter.fromDate()));
        }
        if (filter.toDate() != null) {
            where.append(" AND b.date <= :toDate");
            params.addValue("toDate", java.sql.Date.valueOf(filter.toDate()));
        }
        if (filter.status() != null) {
            where.append(" AND b.status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.paymentStatus() != null) {
            where.append(" AND b.payment_status = :payment");
            params.addValue("payment", filter.paymentStatus());
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            where.append(" AND (b.customer_name ILIKE :search ESCAPE '!' OR b.guest_phone ILIKE :search ESCAPE '!')");
            params.addValue("search", "%" + filter.search().trim().replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%");
        }
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM bookings b" + where, params, Long.class));
        params.addValue("limit", filter.pageSize()).addValue("offset", (long) filter.pageNumber() * filter.pageSize());
        // Both sort fragments come from fixed allowlists, never raw request text.
        String order = " ORDER BY " + filter.sortColumn() + " " + filter.sortDirection()
                + ", b.id " + filter.sortDirection();
        var items = jdbc.query("SELECT b.id,b.booking_code,b.branch_id,b.branch_name,b.court_id,b.court_name,"
                + "b.date,b.customer_name,b.guest_phone,b.status,b.payment_status,b.total_price,b.created_at,b.receipt_image_url"
                + " FROM bookings b" + where + order + " LIMIT :limit OFFSET :offset", params,
                (row, index) -> new AdminBookingView(row.getLong("id"), row.getString("booking_code"),
                        row.getLong("branch_id"), row.getString("branch_name"), row.getLong("court_id"),
                        row.getString("court_name"), row.getObject("date", LocalDate.class), row.getString("customer_name"),
                        row.getString("guest_phone"), row.getString("status"), row.getString("payment_status"),
                        row.getBigDecimal("total_price"), row.getTimestamp("created_at").toInstant(),
                        row.getString("receipt_image_url")));
        return new AdminBookingView.Page(items, filter.pageNumber(), filter.pageSize(), total);
    }
}
