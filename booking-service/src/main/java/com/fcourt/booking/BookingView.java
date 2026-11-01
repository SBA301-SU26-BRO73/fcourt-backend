package com.fcourt.booking;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

record BookingView(long id,String bookingCode,Long branchId,String branchName,String branchAddress,
        String branchPhone,String courtName,LocalDate date,List<Slot> slots,BigDecimal totalPrice,
        String status,String customerName,Instant holdExpiresAt,String checkInCode,String qrImageUrl,
        String receiptImageUrl,String paymentStatus,Instant createdAt,BigDecimal refundAmount,String cancellationReason) {
    record Slot(LocalTime startTime,LocalTime endTime,BigDecimal price) {}
    record Page(List<BookingView> items,int page,int size,long total) {}
}
