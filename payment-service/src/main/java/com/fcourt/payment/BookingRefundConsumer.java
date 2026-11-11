package com.fcourt.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reuses the cancellation outbox; the booking-id lock and UNIQUE constraint make redelivery harmless. */
@Component
@ConditionalOnProperty(name="payment.events-enabled",havingValue="true",matchIfMissing=true)
class BookingRefundConsumer {
    private final ObjectMapper json;
    private final RefundService refunds;

    BookingRefundConsumer(ObjectMapper json,RefundService refunds) {this.json=json;this.refunds=refunds;}

    @KafkaListener(topics="${payment.booking-events-topic:fcourt.booking.events}",groupId="payment-cancellation-refunds")
    public void accept(String payload) throws Exception {
        var event=json.readTree(payload);
        if(!"BookingCancelledEvent".equals(event.path("type").asText()))return;
        var b=event.path("booking");
        boolean guest=b.has("customerId") && b.get("customerId").isNull();
        if(!guest && !"ADMIN".equals(b.path("cancelledByRole").asText()))return;
        if(!"CONFIRMED".equals(b.path("paymentStatus").asText()))return;
        var amount=b.path("refundAmount");
        if(!amount.isNumber())throw new IllegalArgumentException("Missing cancellation refund amount");
        if(amount.decimalValue().signum()<=0)return;
        long id=b.path("id").asLong(),branch=b.path("branchId").asLong();
        if(id<=0 || branch<=0 || !"CANCELLED".equals(b.path("status").asText()))
            throw new IllegalArgumentException("Invalid cancellation booking identity");
        refunds.automatic(new RefundService.Cancellation(id,branch,guest?null:b.path("customerId").longValue(),
            b.path("customerPhone").asText(null),b.path("totalPrice").isNumber()?b.path("totalPrice").decimalValue():null,
            amount.decimalValue(),b.path("reason").asText(null)));
    }
}
