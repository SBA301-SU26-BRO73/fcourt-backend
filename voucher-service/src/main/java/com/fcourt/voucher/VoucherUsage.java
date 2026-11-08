package com.fcourt.voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** customerName, customerPhone and bookingCode are snapshots taken at usage time; null for legacy rows/events. */
record VoucherUsage(UUID id, long voucherId, long bookingId, long userId, BigDecimal discountAmount,
        Instant usedAt, String usedAtSource, String customerName, String customerPhone, String bookingCode) {
    record Page(List<VoucherUsage> items, int page, int size, long total, BigDecimal totalDiscountAmount) {
        Page { items = List.copyOf(items); }
    }
}
