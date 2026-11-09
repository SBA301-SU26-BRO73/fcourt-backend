package com.fcourt.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Hai yếu tố bắt buộc để khách vãng lai tra cứu booking: mã booking và số điện thoại đã dùng khi đặt. */
public record GuestLookupRequest(
        @NotBlank @Pattern(regexp = "(?i)FC-?[0-9]{8}", message = "bookingCode must look like FC12345678") String bookingCode,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}", message = "phone must be 9-15 digits") String phone) {}
