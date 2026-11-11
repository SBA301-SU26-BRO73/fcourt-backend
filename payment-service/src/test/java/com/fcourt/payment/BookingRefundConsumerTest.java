package com.fcourt.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookingRefundConsumerTest {
    final RefundService refunds=mock(RefundService.class);
    final BookingRefundConsumer consumer=new BookingRefundConsumer(new ObjectMapper(),refunds);

    String event(String customer,String role,String status,String amount) {
        return """
            {"type":"BookingCancelledEvent","booking":{"id":42,"branchId":7,"customerId":%s,
            "status":"CANCELLED","paymentStatus":"%s","refundAmount":%s,"cancelledByRole":"%s",
            "customerPhone":"0901234567","reason":"Court closed","totalPrice":100000}}
            """.formatted(customer,status,amount,role);
    }

    @Test void guestPaidCancellationCreatesPolicyAmountWithNullCustomer() throws Exception {
        consumer.accept(event("null","CUSTOMER","CONFIRMED","80000"));
        var request=ArgumentCaptor.forClass(RefundService.Cancellation.class);
        verify(refunds).automatic(request.capture());
        assertThat(request.getValue()).isEqualTo(new RefundService.Cancellation(42,7,null,"0901234567",
            new BigDecimal("100000"),new BigDecimal("80000"),"Court closed"));
    }

    @Test void ownerPaidCancellationCreatesFullRefundForAccountBooking() throws Exception {
        consumer.accept(event("10","ADMIN","CONFIRMED","100000"));
        verify(refunds).automatic(new RefundService.Cancellation(42,7,10L,"0901234567",
            new BigDecimal("100000"),new BigDecimal("100000"),"Court closed"));
    }

    @Test void legacyGuestEventWithoutNewFieldsIsAccepted() throws Exception {
        consumer.accept("""
            {"type":"BookingCancelledEvent","booking":{"id":42,"branchId":7,"customerId":null,
             "status":"CANCELLED","paymentStatus":"CONFIRMED","refundAmount":80000}}
            """);
        verify(refunds).automatic(new RefundService.Cancellation(42,7,null,null,null,new BigDecimal("80000"),null));
    }

    @Test void customerFlowAndUnpaidOrZeroRefundsAreUnaffected() throws Exception {
        consumer.accept(event("10","CUSTOMER","CONFIRMED","80000"));
        consumer.accept(event("null","CUSTOMER","PENDING","100000"));
        consumer.accept(event("null","CUSTOMER","CONFIRMED","0"));
        consumer.accept("{\"type\":\"OtherEvent\"}");
        consumer.accept("""
            {"type":"BookingCancelledEvent","booking":{"id":42,"branchId":7,
             "status":"CANCELLED","paymentStatus":"CONFIRMED","refundAmount":80000}}
            """);
        verifyNoInteractions(refunds);
    }

    @Test void invalidPaidEventFailsRatherThanCreatingWrongRefund() {
        assertThatThrownBy(()->consumer.accept(event("null","CUSTOMER","CONFIRMED","null")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->consumer.accept(event("null","CUSTOMER","CONFIRMED","80000").replace("\"id\":42","\"id\":0")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(refunds);
    }
}
