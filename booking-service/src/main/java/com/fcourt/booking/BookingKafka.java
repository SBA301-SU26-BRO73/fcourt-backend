package com.fcourt.booking;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class BookingKafka {
    // Never acknowledge missing creation or mismatched financial events silently.
    // Alert/repair poison records operationally; retries preserve inbox transaction rollback.
    @Bean DefaultErrorHandler bookingEventErrors() {
        return new DefaultErrorHandler(new FixedBackOff(5000,FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
