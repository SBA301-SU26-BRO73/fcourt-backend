package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class CheckoutApiTest {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    static final String BOOKING = """
        {"id":42,"bookingCode":"BK00000042","branchId":7,"branchName":"Thu Duc",
         "courtName":"Court 1","date":"2030-01-01","totalPrice":160000.00,
         "status":"PENDING_PAYMENT","holdExpiresAt":"2030-01-01T00:05:00Z",
         "slots":[{"startTime":"18:00:00","endTime":"18:30:00","price":75000},
                  {"startTime":"18:30:00","endTime":"19:00:00","price":85000}]}
        """;
    static final String BANK = """
        {"id":7,"bankName":"Vietcombank","bankBin":"970436",
         "bankAccountNumber":"00123456789","bankAccountName":"FCOURT DEMO"}
        """;
    MockRestServiceServer booking;
    MockRestServiceServer facility;
    PaymentUpstreams upstreams;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        var bookingBuilder = RestClient.builder().baseUrl("http://booking");
        var facilityBuilder = RestClient.builder().baseUrl("http://facility");
        booking = MockRestServiceServer.bindTo(bookingBuilder).build();
        facility = MockRestServiceServer.bindTo(facilityBuilder).build();
        upstreams = new PaymentUpstreams(bookingBuilder.build(), facilityBuilder.build());
        mvc = MockMvcBuilders.standaloneSetup(new CheckoutController(
                new CheckoutService(upstreams, Clock.fixed(NOW, ZoneOffset.UTC)))).build();
    }

    void bookingResponse(String body) {
        booking.expect(requestTo("http://booking/api/bookings/42"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-Hold-Token", "guest-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    void bankResponse(String body) {
        facility.expect(requestTo("http://facility/api/public/branches/7"))
                .andExpect(headerDoesNotExist("X-Hold-Token"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void checkoutUsesServerAmountAndPreservesLeadingZeroInBankAccount() throws Exception {
        bookingResponse(BOOKING);
        bankResponse(BANK);
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.amount").value(160000))
                .andExpect(jsonPath("$.transferContent").value("FC BK00000042"))
                .andExpect(jsonPath("$.bankAccountNumber").value("00123456789"))
                .andExpect(jsonPath("$.bankBin").value("970436"))
                .andExpect(jsonPath("$.slots.length()").value(2));
        booking.verify(); facility.verify();
    }

    @Test
    void signedInCredentialsAreForwardedOnlyToBookingAuthority() throws Exception {
        booking.expect(requestTo("http://booking/api/bookings/42"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(BOOKING, MediaType.APPLICATION_JSON));
        bankResponse(BANK);
        mvc.perform(get("/api/payments/checkout-info/42").header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());
        booking.verify(); facility.verify();
    }

    @Test
    void noCredentialsMeansNoUpstreamCall() throws Exception {
        mvc.perform(get("/api/payments/checkout-info/42")).andExpect(status().isUnauthorized());
        booking.verify(); facility.verify();
    }

    @Test
    void missingBookingReturns404() throws Exception {
        booking.expect(requestTo("http://booking/api/bookings/42")).andRespond(withResourceNotFound());
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isNotFound());
        facility.verify();
    }

    @Test
    void bookingAuthorityDenialIsPreserved() throws Exception {
        booking.expect(requestTo("http://booking/api/bookings/42")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "wrong-token"))
                .andExpect(status().isForbidden());
        facility.verify();
    }

    @Test
    void expiryAtCurrentInstantReturns410WithoutReadingBank() throws Exception {
        bookingResponse(BOOKING.replace("00:05:00Z", "00:00:00Z"));
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isGone());
        facility.verify();
    }

    @Test
    void alreadyPaidBookingCannotStartAnotherCheckout() throws Exception {
        bookingResponse(BOOKING.replace("PENDING_PAYMENT", "CONFIRMED"));
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isConflict());
        facility.verify();
    }

    @Test
    void invalidBookingDataNeverReturnsPaymentInstructions() throws Exception {
        for (String body : java.util.List.of("null", "{}", "invalid-json",
                BOOKING.replace("160000.00", "160000.50"),
                BOOKING.replace("160000.00", "-1"), BOOKING.replace("\"id\":42", "\"id\":43"))) {
            setup(); bookingResponse(body);
            mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                    .andExpect(status().isServiceUnavailable());
            facility.verify();
        }
    }

    @Test
    void mismatchedOrIncompleteBankConfigurationFailsClosed() throws Exception {
        for (String body : java.util.List.of("null", "{}", BANK.replace("\"id\":7", "\"id\":8"),
                BANK.replace("970436", "invalid"), BANK.replace("00123456789", ""))) {
            setup(); bookingResponse(BOOKING); bankResponse(body);
            mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void bookingAndFacilityFailuresReturn503() throws Exception {
        booking.expect(requestTo("http://booking/api/bookings/42")).andRespond(withServerError());
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isServiceUnavailable());
        setup(); bookingResponse(BOOKING);
        facility.expect(requestTo("http://facility/api/public/branches/7")).andRespond(withServerError());
        mvc.perform(get("/api/payments/checkout-info/42").header("X-Hold-Token", "guest-token"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void expiryDuringDependencyCallReturns410() throws Exception {
        bookingResponse(BOOKING); bankResponse(BANK);
        Clock moving = mock(Clock.class);
        when(moving.instant()).thenReturn(NOW, NOW.plusSeconds(301));
        var service = new CheckoutService(upstreams, moving);
        try {
            service.checkout(42L, null, "guest-token");
            org.junit.jupiter.api.Assertions.fail("Checkout should have expired in flight");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE);
        }
    }
}
