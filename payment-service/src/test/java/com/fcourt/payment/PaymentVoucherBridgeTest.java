package com.fcourt.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PaymentVoucherBridgeTest {
    @Test void freezeForwardsServiceAndCustomerCredentialsAndVerifiesExactAmount() {
        var builder=RestClient.builder().baseUrl("http://booking");
        var http=MockRestServiceServer.bindTo(builder).build();
        var client=new PaymentUpstreams(builder.build(),RestClient.create(),"integration");
        http.expect(requestTo("http://booking/api/internal/bookings/42/payment-freeze"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-Service-Token","integration"))
                .andExpect(header("Authorization","Bearer customer")).andExpect(header("X-Hold-Token","hold"))
                .andExpect(content().json("{\"amount\":80000}"))
                .andRespond(withSuccess("{\"id\":42,\"totalPrice\":80000,\"paymentFrozen\":true}",MediaType.APPLICATION_JSON));
        client.freeze(42,"Bearer customer","hold",new BigDecimal("80000"));http.verify();
    }
}
