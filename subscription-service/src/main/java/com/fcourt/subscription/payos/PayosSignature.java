package com.fcourt.subscription.payos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signatures PayOS uses for payment requests and webhooks, keyed by the checksum key. */
public final class PayosSignature {
    private static final String ALGORITHM = "HmacSHA256";

    private PayosSignature() {
    }

    /** Signature of a create-payment-link request: the five fields in alphabetical order. */
    public static String forPaymentLink(String checksumKey, long amount, String cancelUrl, String description,
                                        long orderCode, String returnUrl) {
        return hmac(checksumKey, "amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description
                + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl);
    }

    /**
     * Checks the {@code signature} of a webhook against its {@code data} object: every field sorted by
     * name and joined as {@code key=value} with {@code &}, null as empty. Compared in constant time.
     */
    public static boolean verifyWebhook(String checksumKey, JsonNode data, String signature) {
        if (data == null || !data.isObject() || signature == null || signature.isBlank()) {
            return false;
        }
        Map<String, String> fields = new TreeMap<>();
        data.fields().forEachRemaining(field -> fields.put(field.getKey(), canonical(field.getValue())));
        String payload = fields.entrySet().stream().map(field -> field.getKey() + "=" + field.getValue())
                .collect(Collectors.joining("&"));
        byte[] expected = hmac(checksumKey, payload).getBytes(StandardCharsets.UTF_8);
        byte[] received = signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received);
    }

    /**
     * Same rules as the PayOS SDKs: null and the strings {@code "null"} / {@code "undefined"} become empty,
     * an array is its JSON with the keys of every object sorted, anything else is its text.
     */
    private static String canonical(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isArray()) {
            return sortedKeys(value).toString();
        }
        String text = value.isObject() ? value.toString() : value.asText();
        return "null".equals(text) || "undefined".equals(text) ? "" : text;
    }

    private static JsonNode sortedKeys(JsonNode node) {
        if (node.isObject()) {
            var sorted = JsonNodeFactory.instance.objectNode();
            new TreeMap<String, JsonNode>(node.properties().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .forEach((key, child) -> sorted.set(key, sortedKeys(child)));
            return sorted;
        }
        if (node.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(sortedKeys(child)));
            return array;
        }
        return node;
    }

    static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException error) {
            throw new IllegalStateException("Cannot compute PayOS signature", error);
        }
    }
}
