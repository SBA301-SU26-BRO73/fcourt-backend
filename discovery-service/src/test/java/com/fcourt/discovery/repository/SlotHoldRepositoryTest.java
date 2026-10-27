package com.fcourt.discovery.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class SlotHoldRepositoryTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static SlotHoldRepository repository;
    private static final LocalDate DATE = LocalDate.of(2030, 1, 1);

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        repository = new SlotHoldRepository(redis);
    }

    @AfterAll
    static void disconnect() { if (connection != null) connection.destroy(); }

    @Test
    void readsCanonicalKeysAndRedisExpirationWithoutCleanup() {
        redis.opsForValue().set("fcourt:hold:1:" + DATE + ":1", "secret-token", Duration.ofSeconds(300));
        redis.opsForValue().set("fcourt:hold:2:" + DATE + ":2", "another-court", Duration.ofSeconds(300));
        redis.opsForValue().set("fcourt:hold:1:" + DATE.plusDays(1) + ":2", "another-day", Duration.ofSeconds(300));
        assertThat(repository.heldSlots(1, DATE, List.of(1L, 2L))).containsExactly(1L);
        assertThat(redis.getExpire("fcourt:hold:1:" + DATE + ":1")).isBetween(295L, 300L);
        redis.expire("fcourt:hold:1:" + DATE + ":1", Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(repository.heldSlots(1, DATE, List.of(1L, 2L))).isEmpty());
    }

    @Test
    void dependencyFailureReturns503() {
        var broken = org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(broken.opsForValue()).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("unavailable"));
        assertThatThrownBy(() -> new SlotHoldRepository(broken).heldSlots(1, DATE, List.of(1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
}
