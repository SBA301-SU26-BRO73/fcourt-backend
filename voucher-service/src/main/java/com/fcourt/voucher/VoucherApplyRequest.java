package com.fcourt.voucher;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Locale;

record VoucherApplyRequest(@NotBlank @Pattern(regexp="[A-Z0-9_-]{1,50}") String code,
        @NotNull @Positive Long bookingId,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal totalAmount) {
    VoucherApplyRequest { code = code == null ? null : code.trim().toUpperCase(Locale.ROOT); }
}
