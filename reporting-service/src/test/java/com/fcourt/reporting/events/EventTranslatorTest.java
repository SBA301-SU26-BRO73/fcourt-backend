package com.fcourt.reporting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.reporting.events.EventTranslator.BookingFact;
import com.fcourt.reporting.events.EventTranslator.Ignored;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class EventTranslatorTest {
    private final EventTranslator translator = new EventTranslator(new ObjectMapper());
    private final Instant now = Instant.parse("2030-01-01T03:00:00Z");

    static String payment(String type) {
        return """
                {"eventId":"e-1","type":"%s","payment":{"id":5,"bookingId":42,"branchId":7,"amount":100000.00,
                 "status":"CONFIRMED","checkout":{"courtName":" Court A ","customerName":" Alex Nguyen ","date":"2030-01-01","slots":[{"startTime":"18:00"},{"startTime":"18:30"}]}}}
                """.formatted(type);
    }

    @Test
    void mapsPaymentLifecycleToBookingStatusesAndRecognizesRevenueOnConfirmation() {
        var submitted = (BookingFact) translator.payment(payment("PaymentSubmittedEvent"), now);
        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.revenueAt()).isNull();
        assertThat(submitted.slotCount()).isEqualTo(2);
        assertThat(submitted.bookingDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(submitted.courtName()).isEqualTo("Court A");
        assertThat(submitted.customerName()).isEqualTo("Alex Nguyen");
        assertThat(submitted.courtId()).isNull();
        assertThat(submitted.slotStarts()).containsExactly(LocalTime.of(18, 0), LocalTime.of(18, 30));

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
                 "customerName":"Lan Nguyen",
                 "invoice":{"items":[{"slotId":1,"startTime":"19:00","endTime":"19:30"}]}}}}
                """, now);
        assertThat(fact.status()).isEqualTo("CONFIRMED");
        assertThat(fact.revenue()).isEqualByComparingTo("60000");
        assertThat(fact.revenueAt()).isEqualTo(Instant.parse("2030-01-01T02:00:00Z"));
        assertThat(fact.slotCount()).isEqualTo(1);
        assertThat(fact.courtId()).isEqualTo(1L);
        assertThat(fact.customerName()).isEqualTo("Lan Nguyen");
        assertThat(fact.slotStarts()).containsExactly(LocalTime.of(19, 0));

        var checkIn = (BookingFact) translator.staff("""
                {"eventId":"s-2","type":"BookingCheckedInEvent","version":1,"data":{"booking":{"bookingId":42,"branchId":7}}}
                """, now);
        assertThat(checkIn.revenueAt()).isNull();
        assertThat(checkIn.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void toleratesPayloadsWithoutCourtOrSlotTimesButRejectsBadSlotTime() {
        var legacy = (BookingFact) translator.payment("""
                {"eventId":"e-9","type":"PaymentConfirmedEvent","payment":{"bookingId":42,"branchId":7,"amount":1,
                 "checkout":{"date":"2030-01-01","slots":[{"price":1}]}}}
                """, now);
        assertThat(legacy.courtName()).isNull();
        assertThat(legacy.slotStarts()).isEmpty();
        assertThatThrownBy(() -> translator.payment(payment("PaymentConfirmedEvent").replace("18:30", "late"), now))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("startTime");
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
