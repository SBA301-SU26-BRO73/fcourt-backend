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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AutomaticRefundServiceTest {
    final PaymentStore store=mock(PaymentStore.class);
    final PaymentUpstreams authority=mock(PaymentUpstreams.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final List<String> events=new ArrayList<>();
    Map<String,Object> saved;
    int inserts;
    RefundService service;

    @BeforeEach void setup() {
        // Capture database writes and return the resulting row; assertions cover service decisions and event contents.
        var jdbc=mock(JdbcTemplate.class,call->{
            String method=call.getMethod().getName();
            if(!Set.of("queryForList","queryForObject","queryForMap","update").contains(method))return null;
            String sql=call.getArgument(0);
            Object[] a=Arrays.copyOfRange(call.getArguments(),method.equals("queryForObject")?2:1,call.getArguments().length);
            if(sql.contains("INSERT INTO refund_requests")) {
                inserts++;
                saved=new HashMap<>();
                String[] keys={"booking_id","amount","reason","refund_bank_name","bank_bin","refund_bank_account_number",
                    "refund_bank_account_name","customer_id","branch_id","customer_phone"};
                for(int i=0;i<keys.length;i++)saved.put(keys[i],a[i]);
                saved.put("id",5L);saved.put("status","PENDING");
                saved.put("created_at",Timestamp.valueOf("2026-01-01 00:00:00"));saved.put("updated_at",saved.get("created_at"));
                return 5L;
            }
            if(sql.contains("INSERT INTO payment_outbox")){events.add((String)a[3]);return 1;}
            if(sql.contains("UPDATE refund_requests SET refund_bank_name")) {
                saved.put("refund_bank_name",a[0]);saved.put("bank_bin",a[1]);
                saved.put("refund_bank_account_number",a[2]);saved.put("refund_bank_account_name",a[3]);return 1;
            }
            if(method.equals("queryForList"))return saved==null?List.of():List.of(saved);
            if(method.equals("queryForMap"))return saved;
            return null;
        });
        when(store.jdbc()).thenReturn(jdbc);
        when(store.encode(any())).thenAnswer(c->new ObjectMapper().writeValueAsString(c.getArgument(0)));
        when(tx.execute(any())).thenAnswer(c->((TransactionCallback<?>)c.getArgument(0)).doInTransaction(null));
        when(store.find(42)).thenReturn(payment("CONFIRMED",7));
        when(authority.guestBooking(anyString(),anyString())).thenReturn(
            new PaymentUpstreams.GuestBooking(42L,"FC00000042",7L,new BigDecimal("100000"),"CANCELLED","CONFIRMED",new BigDecimal("80000")));
        service=new RefundService(store,authority,tx);
    }

    PaymentRecord payment(String status,long branch) {
        return new PaymentRecord(1,42,branch,new BigDecimal("100000"),status,null,null,null,null,null,null);
    }
    RefundService.Cancellation cancellation(Long customer) {
        return new RefundService.Cancellation(42,7,customer,"0901234567",new BigDecimal("100000"),new BigDecimal("80000"),"Cancelled");
    }
    RefundService.GuestRequest guest(String account) {
        return new RefundService.GuestRequest("fc-00000042","+84901234567","VCB",account,"NGUYEN VAN A",null);
    }

    @Test void guestRefundIsPendingWithoutBankDetailsAndNotifiesOwnerOnce() throws Exception {
        var first=service.automatic(cancellation(null));
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.amount()).isEqualByComparingTo("80000");
        assertThat(first.bankInfoMissing()).isTrue();
        assertThat(first.customerPhone()).isEqualTo("0901234567");
        assertThat(service.automatic(cancellation(null)).id()).isEqualTo(first.id());
        assertThat(inserts).isEqualTo(1);assertThat(events).hasSize(1);
        var event=new ObjectMapper().readTree(events.getFirst());
        assertThat(event.path("type").asText()).isEqualTo("RefundRequestedEvent");
        assertThat(event.path("refund").path("customerId").isNull()).isTrue();
        assertThat(event.path("refund").path("branchId").asLong()).isEqualTo(7);
    }

    @Test void ownerRefundForCustomerUsesSameQueueAndCustomerCanSupplyMissingBankInfo() {
        var automatic=service.automatic(cancellation(10L));
        var jwt=Jwt.withTokenValue("token").header("alg","none").subject("10").build();
        when(authority.booking(42,"Bearer token",null)).thenReturn(new PaymentUpstreams.Booking(42L,"FC00000042",7L,
            null,null,null,null,new BigDecimal("100000"),"CANCELLED",null,null,"0901234567",new BigDecimal("80000"),null));
        assertThat(service.state(42,jwt).eligible()).isTrue();
        var filled=service.request(new RefundService.Request(42,"VCB","970436","123456789","NGUYEN VAN A","Cancelled"),jwt);
        assertThat(filled.id()).isEqualTo(automatic.id());assertThat(filled.bankInfoMissing()).isFalse();
        assertThat(inserts).isEqualTo(1);assertThat(events).hasSize(1);
    }

    @Test void guestCanSubmitBeforeEventArrivesAndLaterUpdateWithoutDuplicate() {
        var first=service.guestRequest(guest("123456789"));
        assertThat(first.bankInfoMissing()).isFalse();
        assertThat(service.automatic(cancellation(null)).id()).isEqualTo(first.id());
        var updated=service.guestRequest(guest("987654321"));
        assertThat(updated.id()).isEqualTo(first.id());assertThat(updated.bankAccountNumber()).isEqualTo("987654321");
        assertThat(inserts).isEqualTo(1);assertThat(events).hasSize(1);
        verify(authority,times(2)).guestBooking("fc-00000042","+84901234567");
    }

    @Test void guestCanFillAutomaticRequestAndIdenticalRetryAfterApprovalIsReadOnly() {
        service.automatic(cancellation(null));
        service.guestRequest(guest("123456789"));
        saved.put("status","APPROVED");
        assertThat(service.guestRequest(guest("123456789")).status()).isEqualTo("APPROVED");
        assertThatThrownBy(()->service.guestRequest(guest("987654321"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(saved.get("refund_bank_account_number")).isEqualTo("123456789");
    }

    @Test void badCodePhoneAndAccountBookingNeverReachRefundStorage() {
        when(authority.guestBooking(anyString(),anyString())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Booking not found"));
        assertThatThrownBy(()->service.guestRequest(guest("123456789"))).hasMessageContaining("404");
        assertThatThrownBy(()->service.guestState(new RefundService.GuestIdentity("FC00000042","0909999999"))).hasMessageContaining("404");
        verifyNoInteractions(store,tx);assertThat(inserts).isZero();
    }

    @Test void nonGuestRefundCannotBeAlteredThroughPublicEndpoint() {
        service.automatic(cancellation(10L));
        assertThatThrownBy(()->service.guestRequest(guest("123456789"))).hasMessageContaining("404");
        assertThat(RefundService.row(saved).bankInfoMissing()).isTrue();
    }

    @Test void cancelledUnpaidAndZeroRefundBookingsCannotSubmitBankDetails() {
        for(var b:List.of(
            new PaymentUpstreams.GuestBooking(42L,"FC00000042",7L,new BigDecimal("100000"),"CONFIRMED","CONFIRMED",new BigDecimal("80000")),
            new PaymentUpstreams.GuestBooking(42L,"FC00000042",7L,new BigDecimal("100000"),"CANCELLED","PENDING",new BigDecimal("80000")),
            new PaymentUpstreams.GuestBooking(42L,"FC00000042",7L,new BigDecimal("100000"),"CANCELLED","CONFIRMED",BigDecimal.ZERO))) {
            when(authority.guestBooking(anyString(),anyString())).thenReturn(b);
            assertThatThrownBy(()->service.guestRequest(guest("123456789"))).hasMessageContaining("409");
        }
        assertThat(inserts).isZero();
    }

    @Test void mismatchedPaymentAndExcessiveRefundNeverCreateRequests() {
        for(var p:List.of(payment("PENDING",7),payment("CONFIRMED",8))) {
            when(store.find(42)).thenReturn(p);
            assertThatThrownBy(()->service.automatic(cancellation(null))).hasMessageContaining("409");
        }
        when(store.find(42)).thenReturn(payment("CONFIRMED",7));
        assertThatThrownBy(()->service.automatic(new RefundService.Cancellation(42,7,null,null,new BigDecimal("100000"),new BigDecimal("100001"),null))).hasMessageContaining("409");
        assertThat(inserts).isZero();
    }

    @Test void lookupReturnsOnlyVerifiedRefundStateAndDoesNotCreateAnything() {
        var identity=new RefundService.GuestIdentity("FC00000042","0901234567");
        assertThat(service.guestState(identity).eligible()).isTrue();
        assertThat(inserts).isZero();
        service.automatic(cancellation(null));
        assertThat(service.guestState(identity).request().bankInfoMissing()).isTrue();
        saved.put("status","COMPLETED");
        assertThat(service.guestState(identity).eligible()).isFalse();
    }
}
