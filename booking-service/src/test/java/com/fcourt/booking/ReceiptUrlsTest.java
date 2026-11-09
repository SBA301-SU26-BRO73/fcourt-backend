package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptUrlsTest {
    static final String LEGACY = "https://res.cloudinary.com/demo/bill.png";

    @Test
    void aKeyBecomesAShortLivedPresignedGetUrl() {
        System.setProperty("aws.accessKeyId", "AKIATEST");
        System.setProperty("aws.secretAccessKey", "secret");
        try {
            var urls = new ReceiptUrls("fcourt-test", "ap-southeast-1", "", false, Duration.ofMinutes(10));

            assertThat(urls.view("receipts/abc.png")).startsWith("https://fcourt-test.s3.ap-southeast-1.amazonaws.com/receipts/abc.png?")
                    .contains("X-Amz-Signature=").contains("X-Amz-Expires=600");
        } finally {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
        }
    }

    @Test
    void legacyUrlsAndNullPassThrough() {
        var urls = new ReceiptUrls("fcourt-test", "ap-southeast-1", "", false, Duration.ofMinutes(10));
        assertThat(urls.view(LEGACY)).isEqualTo(LEGACY);
        assertThat(urls.view((String) null)).isNull();
    }

    @Test
    void withoutABucketTheStoredValueIsReturnedUnchanged() {
        var urls = new ReceiptUrls("", "ap-southeast-1", "", false, Duration.ofMinutes(10));
        assertThat(urls.view("receipts/abc.png")).isEqualTo("receipts/abc.png");
    }

    @Test
    void viewsCarryTheSignedUrlForTheReceiptFieldOnly() {
        var urls = new ReceiptUrls("", "ap-southeast-1", "", false, Duration.ofMinutes(10)) {
            @Override String view(String stored) { return stored == null ? null : "signed:" + stored; }
        };
        var admin = new AdminBookingView(1, "B1", 2, "Branch", 3, "Court", LocalDate.EPOCH, "A", "0900000000", "CONFIRMED",
                "CONFIRMED", BigDecimal.TEN, Instant.EPOCH, "receipts/abc.png");
        var page = urls.view(new AdminBookingView.Page(List.of(admin), 0, 20, 1));
        assertThat(page.items().get(0).receiptImageUrl()).isEqualTo("signed:receipts/abc.png");
        assertThat(page.items().get(0).bookingCode()).isEqualTo("B1");
        assertThat(page.total()).isEqualTo(1);
    }
}
