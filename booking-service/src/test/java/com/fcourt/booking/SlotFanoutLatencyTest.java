package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Docker-free latency run: the real WebSocket/STOMP stack (SlotWebSocket + SlotChanges) with two real
 * clients. Redis Pub/Sub is bypassed by calling the listener directly, so this isolates the broker
 * fan-out; {@link SlotRedisLatencyIntegrationTest} adds the real Redis hop when Docker is available.
 * Loopback, single JVM: not an Android or production acceptance measurement.
 */
@SpringBootTest(classes = SlotFanoutLatencyTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"booking.realtime.enabled=true"})
class SlotFanoutLatencyTest {
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, FlywayAutoConfiguration.class, SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration.class})
    @Import({NoRedisSlotWebSocket.class, SlotChanges.class})
    static class App {
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }

        /** Only needed to build StringRedisTemplate; this variant never opens a Redis connection. */
        @Bean RedisConnectionFactory redisConnectionFactory() { return mock(RedisConnectionFactory.class); }
    }

    // Deliberately NOT annotated @Configuration: LocalFixtureApplication component-scans test-classes in the
    // local-cluster, and these test-only classes must never be picked up there.
    /** The production STOMP configuration, minus the Redis subscription container (needs a live Redis). */
    static class NoRedisSlotWebSocket extends SlotWebSocket {
        NoRedisSlotWebSocket() { super(new String[] {"http://localhost:3000"}); }

        @Override @Bean RedisMessageListenerContainer slotNotifications(RedisConnectionFactory connection, SlotChanges changes) {
            return mock(RedisMessageListenerContainer.class);
        }
    }

    @LocalServerPort int port;
    @Autowired SlotChanges changes;

    @Test
    void measuresBrokerFanoutToTwoClients() throws Exception {
        var result = SlotLatencyHarness.run("fanout-no-redis", port, date -> {
            var message = mock(Message.class);
            when(message.getBody()).thenReturn(("{\"courtId\":" + SlotLatencyHarness.COURT + ",\"date\":\"" + date
                    + "\",\"type\":\"HOLD_CREATED\"}").getBytes(StandardCharsets.UTF_8));
            changes.onMessage(message, null);
        });
        // Generous sanity bound for shared CI hardware; the recorded numbers are the evidence.
        assertThat(result.p95()).isLessThan(500.0);
    }
}
