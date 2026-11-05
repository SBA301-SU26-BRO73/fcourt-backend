package com.fcourt.voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record VoucherRedemption(UUID applicationId, long voucherId, long bookingId, long customerId, long branchId,
        BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal finalAmount,
        Instant expiresAt, String status, Long paymentId) {}
