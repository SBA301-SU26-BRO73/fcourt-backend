package com.fcourt.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentSessionsExpiryTest {
    @Test
    void oneFailingBookingDoesNotBlockOthersInTheBatch() {
        var store = mock(PaymentStore.class);
        var jdbc = mock(JdbcTemplate.class);
        var tx = new TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }
        };
        var now = Instant.parse("2030-01-01T00:10:00Z");
        var expired = new CheckoutInfo(2L, "BK2", 7L, "B", "C", null, List.of(), BigDecimal.TEN,
                "VCB", "970436", "1", "N", "FC BK2", now.minusSeconds(60), null);
        var second = new PaymentRecord(2, 2, 7, BigDecimal.TEN, "PENDING", null, null, null, expired, null, null);
        when(store.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(any(String.class), eq(Long.class), any())).thenReturn(List.of(1L, 2L));
        when(store.find(1L)).thenThrow(new IllegalStateException("poison row"));
        when(store.find(2L)).thenReturn(second);
        new PaymentSessions(store, tx, Clock.fixed(now, ZoneOffset.UTC)).expire();
        verify(store).event(eq("BookingCancelledEvent"), any());
    }
}
