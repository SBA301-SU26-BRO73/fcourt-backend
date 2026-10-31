package com.fcourt.pos.service;

import com.fcourt.pos.api.PosRequests;
import com.fcourt.pos.api.PosViews;
import com.fcourt.pos.integration.PosUpstreams;
import com.fcourt.pos.persistence.PosStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SalesService {
    private final JdbcTemplate jdbc;
    private final PosStore store;
    private final PosUpstreams access;
    private final PosCommands commands;
    private final Clock clock;
    public SalesService(JdbcTemplate jdbc, PosStore store, PosUpstreams access, PosCommands commands, Clock clock) {
        this.jdbc = jdbc; this.store = store; this.access = access; this.commands = commands; this.clock = clock;
    }
    @Transactional(timeout = 20)
    public PosViews.Order create(Jwt jwt, UUID key, PosRequests.Order request) {
        long actor = Long.parseLong(jwt.getSubject());
        long branch = access.staffBranch(jwt);
        commands.lock(actor, key);
        var items = request.items().stream().sorted(Comparator.comparingLong(PosRequests.Item::productId)).toList();
        String hash = commands.fingerprint(new PosRequests.Order(request.paymentMethod(), request.bookingId(), items));
        var previous = jdbc.queryForList("SELECT id, request_fingerprint FROM orders WHERE staff_user_id = ? AND idempotency_key = ?", actor, key);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst().get("request_fingerprint"))) throw commands.conflict();
            return ownOrder(((Number) previous.getFirst().get("id")).longValue(), branch);
        }
        if ("BOOKING".equals(request.paymentMethod()) && request.bookingId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking ID is required for booking payment");
        }
        if (request.bookingId() != null) access.bookingContext(jwt, request.bookingId(), branch);
        var selected = new java.util.ArrayList<PosViews.Product>();
        var ids = new HashSet<Long>();
        BigDecimal total = BigDecimal.ZERO;
        for (var item : items) {
            if (!ids.add(item.productId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate product selection");
            var product = store.product(item.productId(), true);
            if (product.branchId() != branch || !product.active()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product is not available in this branch");
            if (product.stockQuantity() < item.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is out of stock");
            if (product.price().signum() <= 0 || product.price().scale() > 2) throw new ResponseStatusException(HttpStatus.CONFLICT, "Product price is invalid");
            total = total.add(product.price().multiply(BigDecimal.valueOf(item.quantity())));
            selected.add(product);
        }
        if (total.compareTo(new BigDecimal("99999999.99")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order total exceeds supported amount");
        boolean cash = "CASH".equals(request.paymentMethod());
        long id = jdbc.queryForObject("""
                INSERT INTO orders(branch_id, booking_id, staff_user_id, total_amount, payment_method, status, paid_at,
                    order_code, idempotency_key, request_fingerprint, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) RETURNING id
                """, Long.class, branch, request.bookingId(), actor, total, request.paymentMethod(), cash ? "PAID" : "PENDING_CHARGE",
                cash ? Timestamp.from(clock.instant()) : null, "POS-" + UUID.nameUUIDFromBytes(("pos:" + actor + ":" + key)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), key, hash);
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i); var product = selected.get(i);
            jdbc.update("UPDATE products SET stock_quantity = stock_quantity - ?, updated_at = now() WHERE id = ?", item.quantity(), product.id());
            jdbc.update("INSERT INTO order_items(order_id, product_id, quantity, price_at_order, product_name, product_unit) VALUES (?, ?, ?, ?, ?, ?)",
                    id, product.id(), item.quantity(), product.price(), product.name(), product.unit());
            commands.stockMovement(product.id(), actor, key, "SALE", -item.quantity(), "counter sale", id);
        }
        var order = store.order(id, false);
        commands.event(cash ? "PosOrderPaidEvent" : "PosOrderCreatedEvent", order);
        if (!cash) commands.enqueue(UUID.nameUUIDFromBytes((order.orderCode() + ":charge").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                id, "CHARGE", commands.encode(order));
        return order;
    }
    public PosViews.Order get(Jwt jwt, long id) { return ownOrder(id, access.staffBranch(jwt)); }
    public List<PosViews.Order> history(Jwt jwt, int limit, int offset) { return store.history(access.staffBranch(jwt), limit, offset); }
    private PosViews.Order ownOrder(long id, long branch) {
        var order = store.order(id, false);
        if (order.branchId() != branch) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found in this branch");
        return order;
    }
}
