package com.fcourt.payment;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class PaymentKafkaErrors {
    @Bean DefaultErrorHandler cashLedgerErrors(){
        var handler=new DefaultErrorHandler(new FixedBackOff(5000,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addRetryableExceptions(IllegalArgumentException.class);return handler;
    }
}
