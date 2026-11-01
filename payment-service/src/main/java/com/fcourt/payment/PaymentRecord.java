package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.Instant;

record PaymentRecord(long id, long bookingId, long branchId, BigDecimal amount,
        String status, String receiptImageUrl, Instant submittedAt, String rejectionReason,
        CheckoutInfo checkout) { }
