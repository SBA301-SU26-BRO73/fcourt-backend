package com.fcourt.voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record VoucherUsage(UUID id, long voucherId, long bookingId, long userId, BigDecimal discountAmount,
        Instant usedAt, String usedAtSource) {
    record Page(List<VoucherUsage> items, int page, int size, long total, BigDecimal totalDiscountAmount) {
        Page { items = List.copyOf(items); }
    }
}
