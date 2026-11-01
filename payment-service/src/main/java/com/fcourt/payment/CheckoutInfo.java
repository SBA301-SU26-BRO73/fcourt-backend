package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record CheckoutInfo(Long bookingId, String bookingCode, Long branchId,
        String branchName, String courtName, LocalDate date, List<Slot> slots,
        BigDecimal amount, String bankName, String bankBin, String bankAccountNumber,
        String bankAccountName, String transferContent, Instant expiresAt, String customerName) {
    public record Slot(LocalTime startTime, LocalTime endTime, BigDecimal price) { }
}
