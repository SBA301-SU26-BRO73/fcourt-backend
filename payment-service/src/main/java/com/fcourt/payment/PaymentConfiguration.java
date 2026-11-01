package com.fcourt.payment;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class PaymentConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    RestClient bookingClient(@Value("${payment.booking-url}") String url) { return client(url); }

    @Bean
    RestClient facilityClient(@Value("${payment.facility-url}") String url) { return client(url); }

    private RestClient client(String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
