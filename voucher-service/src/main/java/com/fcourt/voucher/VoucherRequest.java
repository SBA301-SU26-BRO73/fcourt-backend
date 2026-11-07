package com.fcourt.voucher;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

record VoucherRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,50}") String code,
        @NotNull @Positive Long branchId,
        @NotNull Voucher.DiscountType discountType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxDiscountAmount,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minOrderAmount,
        @NotNull Instant startDate,
        @NotNull Instant endDate,
        @NotNull @Min(1) Integer usageLimit,
        Voucher.Status status) {
    VoucherRequest {
        code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        minOrderAmount = minOrderAmount == null ? BigDecimal.ZERO : minOrderAmount;
        status = status == null ? Voucher.Status.ACTIVE : status;
    }

    @AssertTrue(message = "startDate must precede endDate")
    public boolean isPeriodValid() {
        return startDate == null || endDate == null || startDate.isBefore(endDate);
    }

    @AssertTrue(message = "percentage discount must not exceed 100")
    public boolean isPercentageValid() {
        return discountType != Voucher.DiscountType.PERCENTAGE || discountValue == null
                || discountValue.compareTo(new BigDecimal("100")) <= 0;
    }
}
