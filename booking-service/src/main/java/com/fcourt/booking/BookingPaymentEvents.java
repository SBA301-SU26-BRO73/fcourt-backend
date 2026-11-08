package com.fcourt.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="booking.events-enabled",havingValue="true",matchIfMissing=true)
class BookingPaymentEvents {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper json;
    BookingPaymentEvents(org.springframework.jdbc.core.JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    @KafkaListener(topics="fcourt.payment.events")
    @Transactional
    public void accept(String payload) throws Exception {
        JsonNode event=json.readTree(payload);
        String type=event.path("type").asText();
        if(!Set.of("PaymentSubmittedEvent","PaymentConfirmedEvent","PaymentRejectedEvent","BookingCancelledEvent").contains(type)) return;
        UUID eventId=UUID.fromString(event.path("eventId").asText());
        if(jdbc.update("INSERT INTO booking_inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING",eventId)==0) return;
        var p=event.path("payment"); long id=p.path("bookingId").asLong();
        if(id<=0 || p.path("id").asLong()<=0 || !p.path("amount").isNumber())
            throw new IllegalArgumentException("Invalid payment event");
        var rows=jdbc.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new IllegalStateException("Booking creation not synchronized: "+id);
        var b=rows.getFirst();
        if(!(b.get("branch_id") instanceof Number branch) || branch.longValue()!=p.path("branchId").asLong()
                || new java.math.BigDecimal(b.get("total_price").toString()).compareTo(p.path("amount").decimalValue())!=0)
            throw new IllegalArgumentException("Payment identity/amount mismatch");
        if(b.get("payment_id") instanceof Number payment && payment.longValue()!=p.path("id").asLong())
            throw new IllegalArgumentException("Payment identity mismatch");
        String current=b.get("status").toString();
        String next=switch(type) {
            case "PaymentSubmittedEvent" -> current.equals("PENDING_PAYMENT") ? "AWAITING_CONFIRMATION":current;
            case "PaymentConfirmedEvent" -> Set.of("PENDING_PAYMENT","AWAITING_CONFIRMATION").contains(current)?"CONFIRMED":current;
            case "PaymentRejectedEvent","BookingCancelledEvent" -> Set.of("PENDING_PAYMENT","AWAITING_CONFIRMATION").contains(current)?"CANCELLED":current;
            default -> current;
        };
        boolean paymentConfirmed=type.equals("PaymentConfirmedEvent");
        boolean confirm=paymentConfirmed && next.equals("CONFIRMED");
        String code=confirm?p.path("checkInCode").asText(""):null;
        if(paymentConfirmed && (!p.path("checkInCode").asText("").matches("[0-9]{32}")
                || !p.path("qrImageUrl").asText().equals("/api/payments/"+id+"/ticket/qr")))
            throw new IllegalArgumentException("Invalid ticket identity");
        // Late events cannot resurrect a cancelled/completed booking or regress a confirmed payment.
        String paymentStatus=paymentConfirmed || "CONFIRMED".equals(b.get("payment_status"))?"CONFIRMED":next.equals("CANCELLED")?"REJECTED":Set.of("CONFIRMED","CHECKED_IN","COMPLETED").contains(next)?"CONFIRMED":p.path("status").asText();
        jdbc.update("""
            UPDATE bookings SET status=?,payment_id=?,payment_status=?,
              checkin_code=COALESCE(checkin_code,?),qr_image_url=COALESCE(qr_image_url,?),
              receipt_image_url=COALESCE(receipt_image_url,?),updated_at=now() WHERE id=?
            """,next,p.path("id").asLong(),paymentStatus,code,confirm?p.path("qrImageUrl").asText():null,
            p.path("receiptImageUrl").isTextual()?p.path("receiptImageUrl").asText():null,id);
        if(next.equals("CANCELLED"))jdbc.update("UPDATE booking_slots SET active=false WHERE booking_id=?",id);
        if(paymentConfirmed && b.get("voucher_application_id") != null) BookingVoucherEvents.confirmed(jdbc,json,b,p);
    }
}
