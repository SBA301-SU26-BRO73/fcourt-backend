package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Latency through a real Redis Pub/Sub hop (Docker/Testcontainers required) with two real STOMP clients.
 * Loopback, single JVM, containerised Redis: local backend evidence only, not Android/network acceptance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"booking.events-enabled=false", "booking.realtime.enabled=true"})
class SlotRedisLatencyIntegrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource static void configure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", db::getJdbcUrl);
        p.add("spring.datasource.username", db::getUsername);
        p.add("spring.datasource.password", db::getPassword);
        p.add("spring.data.redis.host", redis::getHost);
        p.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired StringRedisTemplate publisher;

    @Test
    void measuresRedisToStompLatencyForTwoClients() throws Exception {
        var result = SlotLatencyHarness.run("redis-pubsub", port, date -> publisher.convertAndSend(SlotChanges.CHANNEL,
                "{\"courtId\":" + SlotLatencyHarness.COURT + ",\"date\":\"" + date + "\",\"type\":\"LOCK_CREATED\","
                        + "\"lockType\":\"PRIVATE_EVENT\"}"));
        assertThat(result.p95()).isLessThan(500.0);
    }
}
