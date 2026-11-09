package com.fcourt.booking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Dạng rút gọn cho khách vãng lai. checkInCode và qrImageUrl chỉ có khi status = CONFIRMED. */
public record GuestBookingResponse(
        long id, String bookingCode, String branchName, String branchAddress, String courtName,
        LocalDate date, List<Slot> slots, BigDecimal totalPrice, String status, String paymentStatus,
        String checkInCode, String qrImageUrl) {
    public record Slot(LocalTime startTime, LocalTime endTime, BigDecimal price) {}
}
