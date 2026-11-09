package com.fcourt.booking;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Payment receipts live in a private S3 bucket and booking only keeps the object key it receives in payment events.
 * On the way out of an API the key becomes a short-lived presigned GET URL (default credential chain, read access to
 * {@code receipts/*} only). Absolute URLs (rows from before the S3 move) and null pass through unchanged, and without
 * {@code storage.s3.bucket} the stored value is returned as is.
 */
@Component
class ReceiptUrls {
    private static final Logger log = LoggerFactory.getLogger(ReceiptUrls.class);

    private final String bucket;
    private final Duration ttl;
    private final S3Presigner presigner;

    ReceiptUrls(@Value("${storage.s3.bucket:}") String bucket,
            @Value("${storage.s3.region:ap-southeast-1}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.path-style:false}") boolean pathStyle,
            @Value("${storage.s3.presign-ttl:10m}") Duration ttl) {
        this.bucket = bucket;
        this.ttl = ttl;
        if (bucket.isBlank()) {
            this.presigner = null;
            return;
        }
        var builder = S3Presigner.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle || !endpoint.isBlank()).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        this.presigner = builder.build();
    }

    String view(String stored) {
        if (stored == null || presigner == null || stored.startsWith("https://") || stored.startsWith("http://")) return stored;
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(stored).build()).build()).url().toString();
        } catch (RuntimeException error) {
            log.error("Could not presign receipt {}: {}", stored, error.getClass().getSimpleName());
            return null;
        }
    }

    BookingView view(BookingView b) {
        if (b.receiptImageUrl() == null) return b;
        return new BookingView(b.id(), b.bookingCode(), b.branchId(), b.branchName(), b.branchAddress(), b.branchPhone(), b.courtName(),
                b.date(), b.slots(), b.totalPrice(), b.status(), b.customerName(), b.holdExpiresAt(), b.checkInCode(), b.qrImageUrl(),
                view(b.receiptImageUrl()), b.paymentStatus(), b.createdAt(), b.refundAmount(), b.cancellationReason(), b.courtId(),
                b.voucherApplicationId(), b.voucherId(), b.originalAmount(), b.discountAmount(), b.paymentFrozen(), b.customerPhone());
    }

    BookingView.Page view(BookingView.Page page) {
        return new BookingView.Page(page.items().stream().map(this::view).toList(), page.page(), page.size(), page.total());
    }

    AdminBookingView.Page view(AdminBookingView.Page page) {
        List<AdminBookingView> items = page.items().stream().map(a -> a.receiptImageUrl() == null ? a
                : new AdminBookingView(a.id(), a.bookingCode(), a.branchId(), a.branchName(), a.courtId(), a.courtName(), a.date(),
                        a.customerName(), a.customerPhone(), a.status(), a.paymentStatus(), a.totalPrice(), a.createdAt(),
                        view(a.receiptImageUrl()))).toList();
        return new AdminBookingView.Page(items, page.page(), page.size(), page.total());
    }
}
