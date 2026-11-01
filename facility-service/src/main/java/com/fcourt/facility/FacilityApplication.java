package com.fcourt.facility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FacilityApplication {
    public static void main(String[] args) {
        SpringApplication.run(FacilityApplication.class, args);
    }
}
