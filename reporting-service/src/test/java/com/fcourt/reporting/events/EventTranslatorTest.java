package com.fcourt.reporting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.reporting.events.EventTranslator.BookingFact;
import com.fcourt.reporting.events.EventTranslator.Ignored;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EventTranslatorTest {
    private final EventTranslator translator = new EventTranslator(new ObjectMapper());
    private final Instant now = Instant.parse("2030-01-01T03:00:00Z");

    static String payment(String type) {
        return """
                {"eventId":"e-1","type":"%s","payment":{"id":5,"bookingId":42,"branchId":7,"amount":100000.00,
                 "status":"CONFIRMED","checkout":{"date":"2030-01-01","slots":[{"startTime":"18:00"},{"startTime":"18:30"}]}}}
                """.formatted(type);
    }

    @Test
    void mapsPaymentLifecycleToBookingStatusesAndRecognizesRevenueOnConfirmation() {
        var submitted = (BookingFact) translator.payment(payment("PaymentSubmittedEvent"), now);
        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.revenueAt()).isNull();
        assertThat(submitted.slotCount()).isEqualTo(2);
        assertThat(submitted.bookingDate()).isEqualTo(LocalDate.of(2030, 1, 1));

        var confirmed = (BookingFact) translator.payment(payment("PaymentConfirmedEvent"), now);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.revenue()).isEqualByComparingTo("100000");
        assertThat(confirmed.revenueAt()).isEqualTo(now);

        assertThat(((BookingFact) translator.payment(payment("PaymentRejectedEvent"), now)).status()).isEqualTo("CANCELLED");
        assertThat(((BookingFact) translator.payment(payment("BookingCancelledEvent"), now)).status()).isEqualTo("CANCELLED");
        assertThat(translator.payment(payment("RefundRequestedEvent"), now)).isInstanceOf(Ignored.class);
    }

    @Test
    void mapsStaffWalkInAsConfirmedCashRevenue() {
        var fact = (BookingFact) translator.staff("""
                {"eventId":"s-1","type":"WalkInBookingCreatedEvent","version":1,"data":{"actorId":3,
                 "occurredAt":"2030-01-01T02:00:00Z","booking":{"bookingId":9,"branchId":7,"courtId":1,
                 "date":"2030-01-01","status":"CHECKED_IN","paymentMethod":"CASH","amount":60000,
                 "invoice":{"items":[{"slotId":1}]}}}}
                """, now);
        assertThat(fact.status()).isEqualTo("CONFIRMED");
        assertThat(fact.revenue()).isEqualByComparingTo("60000");
        assertThat(fact.revenueAt()).isEqualTo(Instant.parse("2030-01-01T02:00:00Z"));
        assertThat(fact.slotCount()).isEqualTo(1);

        var checkIn = (BookingFact) translator.staff("""
                {"eventId":"s-2","type":"BookingCheckedInEvent","version":1,"data":{"booking":{"bookingId":42,"branchId":7}}}
                """, now);
        assertThat(checkIn.revenueAt()).isNull();
        assertThat(checkIn.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void rejectsMalformedEnvelopes() {
        assertThatThrownBy(() -> translator.payment("not json", now)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> translator.payment("{\"type\":\"PaymentConfirmedEvent\"}", now))
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> translator.payment(payment("PaymentConfirmedEvent").replace("\"branchId\":7", "\"branchId\":0"), now))
                .hasMessageContaining("branchId");
    }
}
