package com.fcourt.booking;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class BookingApplication {
    public static void main(String[] args) { SpringApplication.run(BookingApplication.class,args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
