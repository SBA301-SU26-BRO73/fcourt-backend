package com.fcourt.pos.persistence;

import com.fcourt.pos.api.PosViews;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PosStore {
    private final JdbcTemplate jdbc;
    public PosStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public PosViews.Product product(long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM products WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::productRow, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        return rows.getFirst();
    }
    public List<PosViews.Product> catalog(long branch, String query, String category, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM products WHERE branch_id = ? AND active AND position(lower(?) in lower(name)) > 0
                AND (? = '' OR category = ?) ORDER BY name, id LIMIT ? OFFSET ?
                """, this::productRow, branch, query, category, category, limit, offset);
    }
    public PosViews.Order order(long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM orders WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::orderRow, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return withItems(rows).getFirst();
    }
    public List<PosViews.Order> history(long branch, int limit, int offset) {
        return withItems(jdbc.query("SELECT * FROM orders WHERE branch_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::orderRow, branch, limit, offset));
    }
    private PosViews.Order orderRow(ResultSet r, int n) throws SQLException {
        return new PosViews.Order(r.getLong("id"), r.getString("order_code"), r.getLong("branch_id"), (Long) r.getObject("booking_id"),
                r.getLong("staff_user_id"), r.getBigDecimal("total_amount"), r.getString("payment_method"), r.getString("status"),
                r.getTimestamp("created_at").toInstant(), r.getTimestamp("paid_at") == null ? null : r.getTimestamp("paid_at").toInstant(),
                r.getLong("version"), List.of());
    }
    private List<PosViews.Order> withItems(List<PosViews.Order> orders) {
        if (orders.isEmpty()) return orders;
        var items = new java.util.HashMap<Long, List<PosViews.Item>>();
        String placeholders = String.join(",", java.util.Collections.nCopies(orders.size(), "?"));
        jdbc.query("SELECT * FROM order_items WHERE order_id IN (" + placeholders + ") ORDER BY product_id", r -> {
            items.computeIfAbsent(r.getLong("order_id"), ignored -> new java.util.ArrayList<>()).add(new PosViews.Item(
                    r.getLong("product_id"), r.getString("product_name"), r.getString("product_unit"), r.getInt("quantity"),
                    r.getBigDecimal("price_at_order"), r.getBigDecimal("price_at_order").multiply(java.math.BigDecimal.valueOf(r.getInt("quantity")))));
        }, orders.stream().map(PosViews.Order::id).toArray());
        return orders.stream().map(o -> new PosViews.Order(o.id(), o.orderCode(), o.branchId(), o.bookingId(), o.cashierUserId(), o.totalAmount(),
                o.paymentMethod(), o.status(), o.createdAt(), o.paidAt(), o.version(), List.copyOf(items.getOrDefault(o.id(), List.of())))).toList();
    }
    private PosViews.Product productRow(ResultSet r, int n) throws SQLException {
        return new PosViews.Product(r.getLong("id"), r.getLong("branch_id"), r.getString("name"), r.getString("unit"),
                r.getString("category"), r.getBigDecimal("price"), r.getBoolean("active"), r.getInt("stock_quantity"), r.getString("image_url"));
    }
}
