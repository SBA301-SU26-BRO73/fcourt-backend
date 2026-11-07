package com.fcourt.voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record Voucher(long id, String code, long branchId, DiscountType discountType, BigDecimal discountValue,
        BigDecimal maxDiscountAmount, BigDecimal minOrderAmount, Instant startDate, Instant endDate,
        int usageLimit, int usedCount, Status status) {
    enum DiscountType { PERCENTAGE, FIXED_AMOUNT }
    enum Status { ACTIVE, EXPIRED }

    Voucher at(Instant now) {
        return new Voucher(id, code, branchId, discountType, discountValue, maxDiscountAmount, minOrderAmount,
                startDate, endDate, usageLimit, usedCount,
                !now.isBefore(endDate) ? Status.EXPIRED : status);
    }

    record Page(List<Voucher> items, int page, int size, long total) {
        Page { items = List.copyOf(items); }
    }
}
