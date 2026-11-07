package com.fcourt.voucher;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class VoucherApplication {
    public static void main(String[] args) { SpringApplication.run(VoucherApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
