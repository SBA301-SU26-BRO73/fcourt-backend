package com.fcourt.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class GuestRefundLifecycleTest {
    final Map<String,Object> row=new HashMap<>();
    final PaymentStore store=mock(PaymentStore.class);
    final BranchOwnership ownership=mock(BranchOwnership.class);
    final ReceiptStorage storage=mock(ReceiptStorage.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final Jwt jwt=Jwt.withTokenValue("owner").header("alg","none").subject("9").build();
    final List<String> events=new ArrayList<>();
    AdminRefundService admin;

    @BeforeEach void setup() {
        row.put("id",5L);row.put("booking_id",42L);row.put("branch_id",7L);row.put("customer_id",null);
        row.put("customer_phone","0901234567");row.put("amount",new BigDecimal("80000"));row.put("status","PENDING");
        row.put("created_at",Timestamp.valueOf("2026-01-01 00:00:00"));row.put("updated_at",row.get("created_at"));
        var jdbc=mock(JdbcTemplate.class,c->{
            if(c.getArguments().length==0 || !(c.getArgument(0) instanceof String sql))return null;
            switch(c.getMethod().getName()) {
                case "queryForList":return List.of(row);
                case "queryForMap":return row;
                case "update":
                    if(sql.contains("UPDATE refund_requests SET status=")) {
                        row.put("status",c.getArgument(1));
                        if(c.getArgument(3)!=null)row.put("refund_proof_image_url",c.getArgument(3));
                    }
                    if(sql.contains("INSERT INTO payment_outbox"))events.add(c.getArgument(4));
                    return 1;
                default:return null;
            }
        });
        when(store.jdbc()).thenReturn(jdbc);
        when(store.encode(any())).thenAnswer(c->new ObjectMapper().writeValueAsString(c.getArgument(0)));
        when(store.find(42)).thenReturn(new PaymentRecord(1,42,7,new BigDecimal("100000"),"CONFIRMED",null,null,null,null,null,null));
        when(tx.execute(any())).thenAnswer(c->((TransactionCallback<?>)c.getArgument(0)).doInTransaction(null));
        when(ownership.actor(jwt)).thenReturn(9L);
        when(storage.uploadRefundProof(any())).thenReturn("refund-proofs/guest.png");
        admin=new AdminRefundService(store,ownership,storage,tx);
    }

    @Test void missingBankRefundAppearsInOwnerQueueWithContactPhoneButCannotBeApproved() {
        var pending=admin.pending(7,0,20,jwt);
        assertThat(pending).hasSize(1);assertThat(pending.getFirst().bankInfoMissing()).isTrue();
        assertThat(pending.getFirst().customerPhone()).isEqualTo("0901234567");
        verify(ownership).require(7,jwt);
        assertThatThrownBy(()->admin.decide(5,"APPROVED",null,null,jwt)).hasMessageContaining("409");
        assertThat(row.get("status")).isEqualTo("PENDING");verifyNoInteractions(storage);
    }

    @Test void guestRefundCanBeApprovedAndCompletedWithNullCustomerAndOneCompletionEvent() throws Exception {
        row.put("refund_bank_name","VCB");row.put("refund_bank_account_number","123456789");row.put("refund_bank_account_name","NGUYEN VAN A");
        assertThat(admin.decide(5,"APPROVED",null,null,jwt).status()).isEqualTo("APPROVED");
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        var proof=new MockMultipartFile("file","proof.png","image/png",out.toByteArray());
        var completed=admin.decide(5,"COMPLETED",null,proof,jwt);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(admin.decide(5,"COMPLETED",null,proof,jwt).id()).isEqualTo(completed.id());
        assertThat(events).hasSize(1);
        var event=new ObjectMapper().readTree(events.getFirst());
        assertThat(event.path("type").asText()).isEqualTo("RefundCompletedEvent");
        assertThat(event.path("refund").path("customerId").isNull()).isTrue();
        assertThat(events.getFirst()).doesNotContain("123456789");
        verify(storage,times(1)).uploadRefundProof(any());
    }
}
