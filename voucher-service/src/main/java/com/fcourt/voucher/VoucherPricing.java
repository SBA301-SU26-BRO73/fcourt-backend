package com.fcourt.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VoucherPricing {
    private final VoucherRepository repository;
    private final Clock clock;
    VoucherPricing(VoucherRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    @Transactional(readOnly = true)
    public Quote validate(String code, long branchId, String orderAmount) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,50}") || branchId <= 0 || orderAmount == null
                || !orderAmount.matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Invalid voucher quote input");
        BigDecimal amount = new BigDecimal(orderAmount).setScale(2);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Order amount must be positive");
        var voucher = repository.byCode(normalized);
        return voucher.map(value -> calculate(value, branchId, amount, clock.instant()))
                .orElseGet(() -> Quote.invalid("NOT_FOUND", amount));
    }

    static Quote calculate(Voucher voucher, long branchId, BigDecimal amount, Instant now) {
        if (voucher.branchId() != branchId) return Quote.invalid("WRONG_BRANCH", amount);
        if (voucher.status() == Voucher.Status.EXPIRED || !now.isBefore(voucher.endDate()))
            return Quote.invalid("EXPIRED", amount);
        if (now.isBefore(voucher.startDate())) return Quote.invalid("NOT_STARTED", amount);
        if (voucher.usedCount() >= voucher.usageLimit()) return Quote.invalid("USAGE_LIMIT_REACHED", amount);
        if (amount.compareTo(voucher.minOrderAmount()) < 0) return Quote.invalid("MIN_ORDER_NOT_MET", amount);
        BigDecimal discount = voucher.discountType() == Voucher.DiscountType.PERCENTAGE
                ? amount.multiply(voucher.discountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : voucher.discountValue();
        if (voucher.maxDiscountAmount() != null) discount = discount.min(voucher.maxDiscountAmount());
        discount = discount.min(amount).setScale(2, RoundingMode.HALF_UP);
        return new Quote(true, "VALID", voucher.id(), discount, amount.subtract(discount).setScale(2, RoundingMode.HALF_UP));
    }

    record Quote(boolean valid, String reason, long voucherId, BigDecimal discountAmount, BigDecimal finalAmount) {
        static Quote invalid(String reason, BigDecimal amount) {
            return new Quote(false, reason, 0, new BigDecimal("0.00"), amount.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
