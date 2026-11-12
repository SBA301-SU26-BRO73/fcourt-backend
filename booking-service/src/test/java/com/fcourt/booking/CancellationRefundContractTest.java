package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CancellationRefundContractTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Map<String,Object> booking=new HashMap<>();
    final List<String> events=new ArrayList<>();
    final BookingQueries queries=mock(BookingQueries.class);
    final Clock clock=Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC);
    JdbcTemplate jdbc;
    BookingCancellation cancellation;

    @BeforeEach void setup() {
        booking.put("id",42L);booking.put("branch_id",7L);booking.put("customer_id",null);
        booking.put("guest_phone","0901234567");booking.put("booking_code","FC00000042");
        booking.put("status","CONFIRMED");booking.put("payment_status","CONFIRMED");booking.put("total_price",new BigDecimal("100000"));
        jdbc=mock(JdbcTemplate.class,call->{
            if(call.getArguments().length==0 || !(call.getArgument(0) instanceof String sql))return null;
            if(call.getMethod().getName().equals("queryForList")) {
                if(sql.contains("FROM bookings"))return List.of(booking);
                return List.of();
            }
            if(call.getMethod().getName().equals("queryForObject"))return LocalDateTime.of(2030,1,3,18,0);
            if(call.getMethod().getName().equals("update")) {
                if(sql.contains("INSERT INTO booking_outbox"))events.add(call.getArgument(3));
                if(sql.startsWith("UPDATE bookings SET status='CANCELLED'")) {
                    booking.put("status","CANCELLED");booking.put("refund_amount",call.getArgument(3));
                }
                return 1;
            }
            return null;
        });
        cancellation=new BookingCancellation(jdbc,queries,json,clock,12,new BigDecimal("80"));
    }

    @Test void paidGuestCancellationPreservesPolicyAndPublishesContactAndEntitlementOnce() throws Exception {
        var result=cancellation.cancelAsGuest("fc-00000042","+84901234567","Change plans");
        assertThat(result.refundAmount()).isEqualByComparingTo("80000");
        cancellation.cancelAsGuest("FC00000042","0901234567",null);
        assertThat(events).hasSize(1);
        var b=json.readTree(events.getFirst()).path("booking");
        assertThat(b.path("customerId").isNull()).isTrue();
        assertThat(b.path("customerPhone").asText()).isEqualTo("0901234567");
        assertThat(b.path("totalPrice").decimalValue()).isEqualByComparingTo("100000");
        assertThat(b.path("refundAmount").decimalValue()).isEqualByComparingTo("80000");
        assertThat(b.path("cancelledByRole").asText()).isEqualTo("CUSTOMER");
    }

    @Test void ownerCancellationPublishesFullRefundForCustomerAndGuestBookings() throws Exception {
        var authority=mock(AdminBranchAuthority.class);var tx=mock(TransactionTemplate.class);
        var jwt=Jwt.withTokenValue("owner").header("alg","none").subject("9").build();
        when(authority.ownedBranches(jwt)).thenReturn(List.of(7L));
        when(tx.execute(any())).thenAnswer(c->((TransactionCallback<?>)c.getArgument(0)).doInTransaction(null));
        var admin=new AdminBookingCancellation(jdbc,cancellation,queries,authority,tx);
        for(Long customer:Arrays.asList(null,10L)) {
            booking.put("status","CONFIRMED");booking.put("customer_id",customer);
            admin.cancel(42,"Court closed",jwt);
            var b=json.readTree(events.getLast()).path("booking");
            assertThat(b.path("refundAmount").decimalValue()).isEqualByComparingTo("100000");
            assertThat(b.path("cancelledByRole").asText()).isEqualTo("ADMIN");
            int count=events.size();admin.cancel(42,"Court closed",jwt);assertThat(events).hasSize(count);
        }
    }

    @Test void wrongGuestPhoneCannotCancelOrEmitRefundEvent() {
        assertThatThrownBy(()->cancellation.cancelAsGuest("FC00000042","0909999999",null)).hasMessageContaining("404");
        assertThat(events).isEmpty();assertThat(booking.get("status")).isEqualTo("CONFIRMED");
    }

    @Test void unpaidGuestCancellationPublishesNoRefundEntitlement() {
        booking.put("status","PENDING_PAYMENT");booking.put("payment_status","PENDING");
        assertThat(cancellation.cancelAsGuest("FC00000042","0901234567",null).refundAmount()).isZero();
    }
}
