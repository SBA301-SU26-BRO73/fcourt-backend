package com.fcourt.subscription.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds PayOS webhook payloads signed the way PayOS documents it. This is deliberately a second
 * implementation of the algorithm, independent of the production code, so the two cross-check each other.
 */
final class WebhookFixtures {
    static final ObjectMapper JSON = new ObjectMapper();

    private WebhookFixtures() {
    }

    /** A successful payment notification for {@code orderCode} and {@code amount}, signed with {@code key}. */
    static JsonNode paid(String key, long orderCode, long amount) {
        return signed(key, successData(orderCode, amount));
    }

    static ObjectNode successData(long orderCode, long amount) {
        ObjectNode data = JSON.createObjectNode();
        data.put("orderCode", orderCode);
        data.put("amount", amount);
        data.put("description", "SUB" + "%06d".formatted(orderCode % 1_000_000));
        data.put("accountNumber", "12345678");
        data.put("reference", "FT" + orderCode);
        data.put("transactionDateTime", "2026-11-01 10:05:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("code", "00");
        data.put("desc", "success");
        data.putNull("counterAccountName");
        return data;
    }

    static JsonNode signed(String key, ObjectNode data) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("code", "00");
        payload.put("desc", "success");
        payload.put("success", true);
        payload.set("data", data);
        payload.put("signature", sign(key, data));
        return payload;
    }

    static String sign(String key, JsonNode data) {
        Map<String, String> fields = new TreeMap<>();
        data.fields().forEachRemaining(field -> fields.put(field.getKey(),
                field.getValue().isNull() ? "" : field.getValue().asText()));
        String payload = fields.entrySet().stream().map(field -> field.getKey() + "=" + field.getValue())
                .collect(Collectors.joining("&"));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
