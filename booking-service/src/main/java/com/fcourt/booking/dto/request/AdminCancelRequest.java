package com.fcourt.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lý do chủ sân hủy booking (sân hỏng, đóng cửa...). Được lưu và gửi kèm BookingCancelledEvent. */
public record AdminCancelRequest(@NotBlank @Size(max = 500) String reason) {}
