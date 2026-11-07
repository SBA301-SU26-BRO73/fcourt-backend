package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.Instant;

record PaymentRecord(long id, long bookingId, long branchId, BigDecimal amount,
        String status, String receiptImageUrl, Instant submittedAt, String rejectionReason,
        CheckoutInfo checkout, String checkInCode, String qrImageUrl, Long customerId) {
    PaymentRecord(long id, long bookingId, long branchId, BigDecimal amount,
            String status, String receiptImageUrl, Instant submittedAt, String rejectionReason,
            CheckoutInfo checkout, String checkInCode, String qrImageUrl) {
        this(id, bookingId, branchId, amount, status, receiptImageUrl, submittedAt,
                rejectionReason, checkout, checkInCode, qrImageUrl, null);
    }
}
