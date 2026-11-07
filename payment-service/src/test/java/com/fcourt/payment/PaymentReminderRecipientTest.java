package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentReminderRecipientTest {
    @Test
    void confirmedPaymentEventCarriesStoredCustomerEmail() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new PaymentStore(jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ApplicationEventPublisher.class));
        var checkout = new CheckoutInfo(42L, "BK42", 7L, "Branch", "Court",
                LocalDate.parse("2026-11-08"),
                List.of(new CheckoutInfo.Slot(LocalTime.parse("18:00"), LocalTime.parse("19:00"),
                        BigDecimal.TEN)), BigDecimal.TEN, "Bank", "123456", "123", "FCOURT",
                "FC BK42", Instant.parse("2026-11-07T12:00:00Z"), "Player", "player@example.com", 17L);
        var payment = new PaymentRecord(9, 42, 7, BigDecimal.TEN, "CONFIRMED", null,
                Instant.now(), null, checkout, "TICKET", null, 17L);

        store.event("PaymentConfirmedEvent", payment);

        var payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("INSERT INTO payment_outbox"), any(), eq(42L),
                eq("PaymentConfirmedEvent"), payload.capture());
        var event = new ObjectMapper().readTree(payload.getValue());
        assertThat(event.path("recipient").path("email").asText()).isEqualTo("player@example.com");
        assertThat(event.path("recipient").path("fullName").asText()).isEqualTo("Player");
    }

    @Test
    void emailIsOmittedWhenPaymentBelongsToAnotherAccount() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new PaymentStore(jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ApplicationEventPublisher.class));
        var checkout = new CheckoutInfo(42L, "BK42", 7L, "Branch", "Court",
                LocalDate.parse("2026-11-08"), List.of(), BigDecimal.TEN, "Bank", "123456", "123",
                "FCOURT", "FC BK42", Instant.parse("2026-11-07T12:00:00Z"), "Player",
                "other@example.com", 18L);
        var payment = new PaymentRecord(9, 42, 7, BigDecimal.TEN, "CONFIRMED", null,
                Instant.now(), null, checkout, "TICKET", null, 17L);

        store.event("PaymentConfirmedEvent", payment);

        var payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("INSERT INTO payment_outbox"), any(), eq(42L),
                eq("PaymentConfirmedEvent"), payload.capture());
        assertThat(new ObjectMapper().readTree(payload.getValue()).has("recipient")).isFalse();
    }
}
