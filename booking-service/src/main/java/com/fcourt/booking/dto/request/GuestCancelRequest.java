package com.fcourt.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Khách vãng lai hủy booking: mã booking + số điện thoại đã dùng khi đặt, lý do không bắt buộc. */
public record GuestCancelRequest(
        @NotBlank @Pattern(regexp = "(?i)FC-?[0-9]{8}", message = "bookingCode must look like FC12345678") String bookingCode,
        @NotBlank @Pattern(regexp = "[+]?[0-9]{9,15}", message = "phone must be 9-15 digits") String phone,
        @Size(max = 500, message = "reason must be at most 500 characters") String reason) {}
