package com.fcourt.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    PaymentStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    JdbcTemplate jdbc() { return jdbc; }

    PaymentRecord find(long bookingId) {
        var rows = jdbc.query("SELECT * FROM payments WHERE booking_id = ?", this::row, bookingId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    PaymentRecord submit(CheckoutInfo checkout, String url) {
        jdbc.update("""
                INSERT INTO payments(booking_id,branch_id,amount,bill_image_url,checkout,submitted_at)
                VALUES (?,?,?,?,?::jsonb,now()) ON CONFLICT (booking_id) DO UPDATE SET bill_image_url=EXCLUDED.bill_image_url,
                    checkout=EXCLUDED.checkout,amount=EXCLUDED.amount,submitted_at=now(),updated_at=now()
                WHERE payments.status='PENDING' AND payments.submitted_at IS NULL
                """, checkout.bookingId(), checkout.branchId(), checkout.amount(), url, encode(checkout));
        return find(checkout.bookingId());
    }
    void issueTicket(long bookingId) {
        jdbc.update("""
                UPDATE payments SET check_in_code=?,qr_image_url=? WHERE booking_id=?
                    AND status='CONFIRMED' AND check_in_code IS NULL
                """,TicketQr.newCode(),"/api/payments/"+bookingId+"/ticket/qr",bookingId);
    }
    void event(String type, PaymentRecord payment) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_outbox(event_id,booking_id,event_type,payload)
                VALUES (?,?,?,?::jsonb)
                """, eventId, payment.bookingId(), type, encode(Map.of(
                    "eventId", eventId, "type", type, "payment", payment)));
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid payment data", e); }
    }
    PaymentRecord row(ResultSet rs, int n) throws SQLException {
        try {
            var time = rs.getTimestamp("submitted_at");
            var checkout = rs.getString("checkout");
            return new PaymentRecord(rs.getLong("id"),rs.getLong("booking_id"),rs.getLong("branch_id"),
                    rs.getBigDecimal("amount"),rs.getString("status"),rs.getString("bill_image_url"),
                    time == null ? null : time.toInstant(),rs.getString("rejection_reason"),
                    checkout == null ? null : json.readValue(checkout, CheckoutInfo.class),
                    rs.getString("check_in_code"),rs.getString("qr_image_url"));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored checkout", e); }
    }
}
