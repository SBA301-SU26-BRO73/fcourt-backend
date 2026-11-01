package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminRefundProofCleanupTest {
    static final String URL = "https://res.cloudinary.com/demo/image/upload/v1/fcourt/receipts/proof.png";
    final PaymentStore store = mock(PaymentStore.class);
    final BranchOwnership ownership = mock(BranchOwnership.class);
    final ReceiptStorage storage = mock(ReceiptStorage.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("9").build();

    Map<String, Object> refund(String status, String proof) {
        var row = new java.util.HashMap<String, Object>();
        row.put("id", 5L);
        row.put("booking_id", 42L);
        row.put("branch_id", 7L);
        row.put("amount", new BigDecimal("100000"));
        row.put("status", status);
        row.put("refund_proof_image_url", proof);
        row.put("created_at", Timestamp.valueOf("2026-01-01 00:00:00"));
        row.put("updated_at", Timestamp.valueOf("2026-01-01 00:00:00"));
        return row;
    }

    AdminRefundService service(String firstStatus, String lockedStatus, String lockedProof) {
        var jdbc = mock(JdbcTemplate.class, call -> switch (call.getMethod().getName()) {
            case "queryForList" -> List.of(refund(firstStatus, null));
            case "queryForMap" -> refund(lockedStatus, lockedProof);
            default -> null;
        });
        when(store.jdbc()).thenReturn(jdbc);
        when(ownership.actor(any())).thenReturn(9L);
        when(storage.upload(any())).thenReturn(URL);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        return new AdminRefundService(store, ownership, storage, tx);
    }

    MockMultipartFile image() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "proof.png", "image/png", out.toByteArray());
    }

    @Test
    void proofIsDeletedWhenPaymentNoLongerMatches() throws Exception {
        var service = service("APPROVED", "APPROVED", null);
        assertThatThrownBy(() -> service.decide(5, "COMPLETED", null, image(), jwt)).isInstanceOf(ResponseStatusException.class);
        verify(storage).delete(URL);
    }

    @Test
    void proofIsDeletedWhenAnotherRequestCompletedFirst() throws Exception {
        var service = service("APPROVED", "COMPLETED", "https://res.cloudinary.com/demo/other.png");
        var result = service.decide(5, "COMPLETED", null, image(), jwt);
        assertThat(result.transferProofUrl()).isEqualTo("https://res.cloudinary.com/demo/other.png");
        verify(storage).delete(URL);
    }
}
