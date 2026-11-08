package com.fcourt.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

record AdminBookingView(long id, String bookingCode, long branchId, String branchName,
        long courtId, String courtName, LocalDate date, String customerName, String customerPhone,
        String status, String paymentStatus, BigDecimal totalPrice, Instant createdAt,
        String receiptImageUrl) {
    record Page(List<AdminBookingView> items, int page, int size, long total) {
        Page { items = List.copyOf(items); }
    }
}
