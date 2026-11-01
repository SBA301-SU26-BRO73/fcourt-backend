package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.Instant;

record RefundView(long id,long bookingId,BigDecimal amount,String status,String bankName,String bankBin,
        String bankAccountNumber,String bankAccountName,String reason,String adminNote,String transferProofUrl,
        Instant createdAt,Instant updatedAt) {}
