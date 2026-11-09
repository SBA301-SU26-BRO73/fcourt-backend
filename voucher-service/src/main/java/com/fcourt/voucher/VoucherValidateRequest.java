package com.fcourt.voucher;

import jakarta.validation.constraints.*;
import java.util.Locale;

record VoucherValidateRequest(@NotBlank @Pattern(regexp="[A-Z0-9_-]{1,50}") String code, @NotNull @Positive Long bookingId) {
    VoucherValidateRequest { code = code == null ? null : code.trim().toUpperCase(Locale.ROOT); }
}
