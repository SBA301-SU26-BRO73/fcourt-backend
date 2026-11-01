package com.fcourt.subscription.payos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fcourt.subscription.payos.PayosClient.PaymentGatewayException;
import com.fcourt.subscription.payos.PayosClient.PaymentLinkRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestPayosClientTest {
    static final Instant EXPIRES = Instant.parse("2026-11-01T10:15:00Z");
    static final PayosProperties PROPERTIES = new PayosProperties("client-123", "api-key-456", "checksum-secret",
            "https://api-merchant.payos.vn", "https://app/return", "https://app/cancel", Duration.ofMinutes(15), null);

    MockRestServiceServer server;
    RestPayosClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPayosClient(builder, PROPERTIES);
    }

    @Test
    void sendsASignedRequestWithTheCredentialHeadersAndReturnsTheLink() {
        String signature = PayosSignature.forPaymentLink("checksum-secret", 50000, "https://app/cancel",
                "SUB000123", 123, "https://app/return");
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-client-id", "client-123"))
                .andExpect(header("x-api-key", "api-key-456"))
                .andExpect(jsonPath("$.orderCode").value(123))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.description").value("SUB000123"))
                .andExpect(jsonPath("$.returnUrl").value("https://app/return"))
                .andExpect(jsonPath("$.cancelUrl").value("https://app/cancel"))
                .andExpect(jsonPath("$.expiredAt").value(EXPIRES.getEpochSecond()))
                .andExpect(jsonPath("$.items[0].name").value("Pro"))
                .andExpect(jsonPath("$.items[0].price").value(50000))
                .andExpect(jsonPath("$.signature").value(signature))
                .andRespond(withSuccess("""
                        {"code":"00","desc":"success","data":{"paymentLinkId":"link-1",
                         "checkoutUrl":"https://pay.payos.vn/web/link-1","qrCode":"000201..."}}""",
                        MediaType.APPLICATION_JSON));

        var link = client.createLink(new PaymentLinkRequest(123, 50000, "SUB000123", "Pro", EXPIRES));

        assertThat(link.paymentLinkId()).isEqualTo("link-1");
        assertThat(link.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/link-1");
        assertThat(link.qrCode()).isEqualTo("000201...");
        server.verify();
    }

    @Test
    void aGatewayRefusalIsReportedWithItsCodeButNeverWithCredentials() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess("{\"code\":\"231\",\"desc\":\"order exists\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createLink(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("PayOS rejected the payment link request: code=231");
    }

    @Test
    void aServerErrorBecomesAGatewayExceptionWithoutLeakingTheApiKey() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.createLink(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageStartingWith("PayOS request failed")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("api-key-456", "client-123",
                        "checksum-secret"));
    }

    @Test
    void anAnswerWithoutACheckoutUrlIsRejected() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"paymentLinkId\":\"link-1\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createLink(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("PayOS answered without a checkout URL");
    }

    @Test
    void anEmptyBodyIsRejected() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.createLink(request())).isInstanceOf(PaymentGatewayException.class);
    }

    static PaymentLinkRequest request() {
        return new PaymentLinkRequest(123, 50000, "SUB000123", "Pro", EXPIRES);
    }
}
