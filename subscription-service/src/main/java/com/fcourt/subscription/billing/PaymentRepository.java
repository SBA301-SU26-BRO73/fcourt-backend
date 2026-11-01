package com.fcourt.subscription.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code subscription_payments}, one row per PayOS checkout keyed by {@code order_code}. */
@Repository
class PaymentRepository {
    static final String PENDING = "PENDING";
    static final String PAID = "PAID";
    static final String EXPIRED = "EXPIRED";
    static final String FAILED = "FAILED";

    private final JdbcTemplate jdbc;

    PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record PaymentRecord(long id, long orderCode, long adminId, long planId, String planName, String billingCycle,
                         long amount, String status, String checkoutUrl, String qrCode, OffsetDateTime expiredAt,
                         OffsetDateTime paidAt) {
    }

    /** The unique index on {@code order_code} makes a clash throw {@code DuplicateKeyException}. */
    long insertPending(long orderCode, long adminId, long planId, String planName, String billingCycle,
                       long amount, Instant expiredAt, Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO subscription_payments (order_code, admin_id, plan_id, plan_name, billing_cycle, amount,
                    status, expired_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) RETURNING id""", Long.class,
                orderCode, adminId, planId, planName, billingCycle, amount, Timestamp.from(expiredAt),
                Timestamp.from(at), Timestamp.from(at));
        return id == null ? 0 : id;
    }

    Optional<PaymentRecord> findByOrderCode(long orderCode) {
        return jdbc.query("""
                SELECT id, order_code, admin_id, plan_id, plan_name, billing_cycle, amount, status, checkout_url,
                       qr_code, expired_at, paid_at
                FROM subscription_payments WHERE order_code = ?""", this::payment, orderCode).stream().findFirst();
    }

    Optional<PaymentRecord> findById(long id) {
        return jdbc.query("""
                SELECT id, order_code, admin_id, plan_id, plan_name, billing_cycle, amount, status, checkout_url,
                       qr_code, expired_at, paid_at
                FROM subscription_payments WHERE id = ?""", this::payment, id).stream().findFirst();
    }

    void saveLink(long id, String paymentLinkId, String checkoutUrl, String qrCode, Instant at) {
        jdbc.update("UPDATE subscription_payments SET payment_link_id = ?, checkout_url = ?, qr_code = ?, "
                + "updated_at = ? WHERE id = ?", paymentLinkId, checkoutUrl, qrCode, Timestamp.from(at), id);
    }

    void markFailed(long id, Instant at) {
        jdbc.update("UPDATE subscription_payments SET status = 'FAILED', updated_at = ? WHERE id = ? "
                + "AND status = 'PENDING'", Timestamp.from(at), id);
    }

    /** Lazily turns a PENDING checkout whose time ran out into EXPIRED; returns whether a row changed. */
    boolean markExpiredIfDue(long orderCode, Instant now) {
        return jdbc.update("UPDATE subscription_payments SET status = 'EXPIRED', updated_at = ? "
                + "WHERE order_code = ? AND status = 'PENDING' AND expired_at <= ?",
                Timestamp.from(now), orderCode, Timestamp.from(now)) == 1;
    }

    /**
     * Atomic PENDING or EXPIRED to PAID. Returns false when another delivery of the same webhook already
     * did it, so the subscription is only activated once.
     */
    boolean markPaid(long orderCode, String reference, Instant paidAt, String webhookPayload) {
        return jdbc.update("""
                UPDATE subscription_payments SET status = 'PAID', payos_reference = ?, paid_at = ?,
                    webhook_payload = ?, updated_at = ?
                WHERE order_code = ? AND status IN ('PENDING', 'EXPIRED')""",
                reference, Timestamp.from(paidAt), webhookPayload, Timestamp.from(paidAt), orderCode) == 1;
    }

    private PaymentRecord payment(ResultSet row, int index) throws SQLException {
        return new PaymentRecord(row.getLong("id"), row.getLong("order_code"), row.getLong("admin_id"),
                row.getLong("plan_id"), row.getString("plan_name"), row.getString("billing_cycle"),
                row.getBigDecimal("amount").longValueExact(), row.getString("status"),
                row.getString("checkout_url"), row.getString("qr_code"),
                row.getObject("expired_at", OffsetDateTime.class), row.getObject("paid_at", OffsetDateTime.class));
    }
}
