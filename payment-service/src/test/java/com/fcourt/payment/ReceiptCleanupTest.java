package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReceiptCleanupTest {
    static final String URL = "https://res.cloudinary.com/demo/image/upload/v1/fcourt/receipts/abc.png";
    final CheckoutService checkout = mock(CheckoutService.class);
    final PaymentUpstreams upstreams = mock(PaymentUpstreams.class);
    final PaymentStore store = mock(PaymentStore.class);
    final ReceiptStorage storage = mock(ReceiptStorage.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);

    ReceiptService service(Instant now) throws Exception {
        var booking = new ObjectMapper().findAndRegisterModules().readValue(CheckoutApiTest.BOOKING, PaymentUpstreams.Booking.class);
        when(upstreams.booking(42L, null, "guest")).thenReturn(booking);
        var info = new CheckoutInfo(42L, "BK00000042", 7L, "Thu Duc", "Court 1", booking.date(), booking.slots(),
                booking.totalPrice(), "VCB", "970436", "00123", "FCOURT", "FC BK00000042", booking.holdExpiresAt(), booking.customerName());
        when(checkout.checkout(42L, null, "guest")).thenReturn(info);
        when(storage.upload(any())).thenReturn(URL);
        when(store.jdbc()).thenReturn(mock(JdbcTemplate.class));
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        return new ReceiptService(checkout, upstreams, store, storage, tx, Clock.fixed(now, ZoneOffset.UTC));
    }

    MockMultipartFile image() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "bill.png", "image/png", out.toByteArray());
    }

    @Test
    void uploadedImageIsDeletedWhenHoldExpiredBeforeCommit() throws Exception {
        var service = service(CheckoutApiTest.NOW.plusSeconds(3600));
        assertThatThrownBy(() -> service.upload(42, null, "guest", image())).isInstanceOf(ResponseStatusException.class);
        verify(storage).delete(URL);
    }

    @Test
    void uploadedImageIsDeletedWhenConcurrentUploadWonTheRace() throws Exception {
        var service = service(CheckoutApiTest.NOW);
        var winner = new PaymentRecord(1, 42, 7, java.math.BigDecimal.TEN, "PENDING", "https://res.cloudinary.com/demo/other.png", java.time.Instant.EPOCH, null, null, null, null);
        when(store.find(42L)).thenReturn(null, winner);
        service.upload(42, null, "guest", image());
        verify(storage).delete(URL);
    }

    @Test
    void imageIsKeptWhenPaymentSaved() throws Exception {
        var service = service(CheckoutApiTest.NOW);
        var saved = new PaymentRecord(1, 42, 7, java.math.BigDecimal.TEN, "PENDING", URL, java.time.Instant.EPOCH, null, null, null, null);
        when(store.find(42L)).thenReturn(null, null);
        when(store.submit(any(), any())).thenReturn(saved);
        service.upload(42, null, "guest", image());
        verify(storage, never()).delete(any());
    }
}
