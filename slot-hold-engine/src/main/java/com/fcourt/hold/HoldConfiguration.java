package com.fcourt.hold;

import java.time.Clock;
import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class HoldConfiguration {
    @Bean(destroyMethod = "shutdown")
    RedissonClient redisson(@Value("${hold.redis-address}") String address,
                            @Value("${hold.redis-password:}") String password) {
        Config config = new Config();
        var server = config.useSingleServer().setAddress(address)
                .setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(0);
        if (!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }

    @Bean
    RestClient discoveryClient(@Value("${hold.discovery-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
