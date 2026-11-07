package com.fcourt.voucher;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class VoucherUsageKafka {
    @Bean DefaultErrorHandler voucherUsageErrors() {
        // Preserve rejected financial records for repair/replay rather than acknowledging lost usage.
        return new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
