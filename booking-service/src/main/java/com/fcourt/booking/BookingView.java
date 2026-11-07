package com.fcourt.booking;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

record BookingView(long id,String bookingCode,Long branchId,String branchName,String branchAddress,
        String branchPhone,String courtName,LocalDate date,List<Slot> slots,BigDecimal totalPrice,
        String status,String customerName,Instant holdExpiresAt,String checkInCode,String qrImageUrl,
        String receiptImageUrl,String paymentStatus,Instant createdAt,BigDecimal refundAmount,String cancellationReason,
        long courtId, java.util.UUID voucherApplicationId,Long voucherId,BigDecimal originalAmount,BigDecimal discountAmount,
        boolean paymentFrozen) {
    record Slot(LocalTime startTime,LocalTime endTime,BigDecimal price) {}
    BookingView withSlots(List<Slot> loaded) {
        return new BookingView(id,bookingCode,branchId,branchName,branchAddress,branchPhone,courtName,date,loaded,totalPrice,
            status,customerName,holdExpiresAt,checkInCode,qrImageUrl,receiptImageUrl,paymentStatus,createdAt,refundAmount,cancellationReason,
            courtId,voucherApplicationId,voucherId,originalAmount,discountAmount,paymentFrozen);
    }
    record Page(List<BookingView> items,int page,int size,long total) {}
}
