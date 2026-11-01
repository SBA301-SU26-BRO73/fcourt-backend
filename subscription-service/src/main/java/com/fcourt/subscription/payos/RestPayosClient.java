package com.fcourt.subscription.payos;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** PayOS v2 {@code POST /v2/payment-requests}. Timeouts come from {@code spring.http.client.*}. */
@Component
class RestPayosClient implements PayosClient {
    private static final String SUCCESS = "00";

    private final RestClient http;
    private final PayosProperties properties;

    RestPayosClient(RestClient.Builder builder, PayosProperties properties) {
        this.http = builder.baseUrl(properties.apiUrl()).build();
        this.properties = properties;
    }

    @Override
    public PaymentLink createLink(PaymentLinkRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", request.orderCode());
        body.put("amount", request.amount());
        body.put("description", request.description());
        body.put("cancelUrl", properties.cancelUrl());
        body.put("returnUrl", properties.returnUrl());
        body.put("expiredAt", request.expiresAt().getEpochSecond());
        body.put("items", List.of(Map.of("name", request.itemName(), "quantity", 1, "price", request.amount())));
        body.put("signature", PayosSignature.forPaymentLink(properties.checksumKey(), request.amount(),
                properties.cancelUrl(), request.description(), request.orderCode(), properties.returnUrl()));

        JsonNode response;
        try {
            response = http.post().uri("/v2/payment-requests")
                    .header("x-client-id", properties.clientId())
                    .header("x-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException error) {
            // The message of a client exception can echo request headers, so only the type is kept.
            throw new PaymentGatewayException("PayOS request failed: " + error.getClass().getSimpleName());
        }
        if (response == null || !SUCCESS.equals(response.path("code").asText()) || !response.path("data").isObject()) {
            String code = response == null ? "no-response" : response.path("code").asText("unknown");
            throw new PaymentGatewayException("PayOS rejected the payment link request: code=" + code);
        }
        JsonNode data = response.get("data");
        String checkoutUrl = text(data, "checkoutUrl");
        if (checkoutUrl == null) {
            throw new PaymentGatewayException("PayOS answered without a checkout URL");
        }
        return new PaymentLink(text(data, "paymentLinkId"), checkoutUrl, text(data, "qrCode"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
