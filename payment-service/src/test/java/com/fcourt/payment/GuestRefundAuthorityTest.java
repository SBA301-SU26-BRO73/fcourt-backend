package com.fcourt.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GuestRefundAuthorityTest {
    final RestClient.Builder builder=RestClient.builder().baseUrl("http://booking");
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final PaymentUpstreams authority=new PaymentUpstreams(builder.build(),RestClient.create());

    @Test void codeAndPhoneAreSentToExistingGuestOnlyLookupAndExtraFieldsAreIgnored() {
        server.expect(requestTo("http://booking/api/public/bookings/lookup"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().json("{\"bookingCode\":\"fc-00000042\",\"phone\":\"+84901234567\"}"))
            .andRespond(withSuccess("""
                {"id":42,"bookingCode":"FC00000042","branchId":7,"totalPrice":100000,
                 "status":"CANCELLED","paymentStatus":"CONFIRMED","refundAmount":80000,"slots":[]}
                """,MediaType.APPLICATION_JSON));
        assertThat(authority.guestBooking("fc-00000042","+84901234567").refundAmount()).isEqualByComparingTo("80000");
        server.verify();
    }

    @Test void wrongCodePhoneAndAccountBookingsAllKeepSameNotFoundMessage() {
        server.expect(requestTo("http://booking/api/public/bookings/lookup")).andRespond(withResourceNotFound());
        assertThatThrownBy(()->authority.guestBooking("FC00000042","0909999999")).hasMessageContaining("404").hasMessageContaining("Booking not found");
    }

    @Test void mismatchedAuthorityIdentityFailsClosed() {
        server.expect(requestTo("http://booking/api/public/bookings/lookup"))
            .andRespond(withSuccess("{\"id\":99,\"bookingCode\":\"FC00000099\",\"branchId\":7}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->authority.guestBooking("FC00000042","0901234567")).hasMessageContaining("503");
    }
}
