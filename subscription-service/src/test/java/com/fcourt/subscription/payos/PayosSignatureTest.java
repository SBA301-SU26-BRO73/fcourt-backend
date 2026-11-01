package com.fcourt.subscription.payos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PayosSignatureTest {
    static final String KEY = "checksum-secret";
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hmacMatchesTheKnownHmacSha256TestVector() {
        // RFC 4231 style vector widely published for HMAC-SHA256.
        assertThat(PayosSignature.hmac("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void paymentLinkSignatureSignsTheFiveFieldsInAlphabeticalOrder() {
        String expected = PayosSignature.hmac(KEY, "amount=50000&cancelUrl=https://app/cancel"
                + "&description=SUB000123&orderCode=123&returnUrl=https://app/return");

        assertThat(PayosSignature.forPaymentLink(KEY, 50000, "https://app/cancel", "SUB000123", 123,
                "https://app/return")).isEqualTo(expected).hasSize(64);
    }

    @Test
    void aWebhookSignedOverTheSortedDataFieldsVerifies() throws Exception {
        JsonNode data = JSON.readTree("""
                {"orderCode":123,"amount":50000,"description":"SUB000123","code":"00","desc":"success",
                 "reference":"FT123","counterAccountName":null}""");
        String signature = PayosSignature.hmac(KEY, "amount=50000&code=00&counterAccountName=&desc=success"
                + "&description=SUB000123&orderCode=123&reference=FT123");

        assertThat(PayosSignature.verifyWebhook(KEY, data, signature)).isTrue();
    }

    @Test
    void theNullAndUndefinedStringsSignAsEmptyLikeThePayosSdks() throws Exception {
        JsonNode data = JSON.readTree("""
                {"orderCode":123,"counterAccountName":"null","virtualAccountName":"undefined","reference":"FT123"}""");
        String signature = PayosSignature.hmac(KEY,
                "counterAccountName=&orderCode=123&reference=FT123&virtualAccountName=");

        assertThat(PayosSignature.verifyWebhook(KEY, data, signature)).isTrue();
    }

    @Test
    void anArrayIsSignedAsJsonWithTheKeysOfEachObjectSorted() throws Exception {
        JsonNode data = JSON.readTree("""
                {"orderCode":123,"items":[{"quantity":1,"name":"Pro","price":299000}]}""");
        String signature = PayosSignature.hmac(KEY,
                "items=[{\"name\":\"Pro\",\"price\":299000,\"quantity\":1}]&orderCode=123");

        assertThat(PayosSignature.verifyWebhook(KEY, data, signature)).isTrue();
    }

    @Test
    void fieldOrderInTheJsonDoesNotMatterAndTheHexCaseIsIgnored() throws Exception {
        JsonNode shuffled = JSON.readTree("{\"reference\":\"FT123\",\"orderCode\":123,\"amount\":50000}");
        String signature = PayosSignature.hmac(KEY, "amount=50000&orderCode=123&reference=FT123");

        assertThat(PayosSignature.verifyWebhook(KEY, shuffled, signature.toUpperCase())).isTrue();
    }

    @Test
    void aChangedFieldTheWrongKeyOrAMissingSignatureAreRejected() throws Exception {
        JsonNode data = JSON.readTree("{\"orderCode\":123,\"amount\":50000}");
        String signature = PayosSignature.hmac(KEY, "amount=50000&orderCode=123");
        JsonNode tampered = JSON.readTree("{\"orderCode\":123,\"amount\":1}");

        assertThat(PayosSignature.verifyWebhook(KEY, tampered, signature)).isFalse();
        assertThat(PayosSignature.verifyWebhook("another-key", data, signature)).isFalse();
        assertThat(PayosSignature.verifyWebhook(KEY, data, null)).isFalse();
        assertThat(PayosSignature.verifyWebhook(KEY, data, " ")).isFalse();
        assertThat(PayosSignature.verifyWebhook(KEY, null, signature)).isFalse();
        assertThat(PayosSignature.verifyWebhook(KEY, JSON.readTree("[1]"), signature)).isFalse();
    }
}
