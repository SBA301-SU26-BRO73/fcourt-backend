package com.fcourt.payment;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@ConditionalOnProperty(name="payment.events-enabled",havingValue="true",matchIfMissing=true)
class CashLedgerConsumer {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    CashLedgerConsumer(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @KafkaListener(topics="${payment.cash-events-topic:fcourt.staff.events}",groupId="payment-cash-ledger")
    @Transactional
    public void accept(String payload) throws Exception {
        var event=json.readTree(payload);if(!"WalkInBookingCreatedEvent".equals(event.path("type").asText()))return;
        if(event.path("version").asInt()!=1)throw invalid();
        UUID eventId=UUID.fromString(event.path("eventId").asText());
        var data=event.path("data");var b=data.path("booking");var invoice=b.path("invoice");
        long booking=b.path("bookingId").asLong(),branch=b.path("branchId").asLong(),court=b.path("courtId").asLong(),actor=data.path("actorId").asLong();
        String code=b.path("bookingCode").asText(),receipt=invoice.path("receiptCode").asText();
        if(booking<=0||branch<=0||court<=0||actor<=0||code.isBlank()||code.length()>10||receipt.isBlank()||receipt.length()>64
            ||!"CASH".equals(b.path("paymentMethod").asText())||!"CHECKED_IN".equals(b.path("status").asText())
            ||invoice.path("cashierUserId").asLong()!=actor||!b.path("amount").isNumber()||!invoice.path("totalAmount").isNumber())throw invalid();
        BigDecimal amount=b.path("amount").decimalValue();
        if(amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("99999999.99"))>0||invoice.path("totalAmount").decimalValue().compareTo(amount)!=0)throw invalid();
        Instant occurred=Instant.parse(data.path("occurredAt").asText());
        if(!occurred.equals(Instant.parse(b.path("checkedInAt").asText())))throw invalid();
        jdbc.update("""
            INSERT INTO walk_in_cash_ledger(event_id,booking_id,branch_id,court_id,booking_code,receipt_code,amount,occurred_at)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """,eventId,booking,branch,court,code,receipt,amount,java.sql.Timestamp.from(occurred));
        var rows=jdbc.queryForList("SELECT * FROM walk_in_cash_ledger WHERE booking_id=? OR event_id=?",booking,eventId);
        if(rows.size()!=1)throw invalid();var r=rows.getFirst();
        if(((Number)r.get("booking_id")).longValue()!=booking||((Number)r.get("branch_id")).longValue()!=branch
            ||((Number)r.get("court_id")).longValue()!=court||!r.get("booking_code").equals(code)||!r.get("receipt_code").equals(receipt)
            ||((BigDecimal)r.get("amount")).compareTo(amount)!=0||!((java.sql.Timestamp)r.get("occurred_at")).toInstant().equals(occurred))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or conflicting staff cash event");}
}
