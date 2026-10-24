package com.fcourt.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HoldIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final List<RedissonClient> CLIENTS = new ArrayList<>();
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    RedissonClient redis;
    RedisHoldStore store;
    MockRestServiceServer discovery;
    MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        redis = client();
        redis.getKeys().flushdb();
        store = new RedisHoldStore(redis, MAPPER);
        var builder = RestClient.builder().baseUrl("http://discovery");
        discovery = MockRestServiceServer.bindTo(builder).build();
        mvc = MockMvcBuilders.standaloneSetup(new HoldController(
                new HoldService(new DiscoveryGateway(builder.build(), CLOCK), store)))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(MAPPER))
                .build();
    }

    static RedissonClient client() {
        var config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        var result = Redisson.create(config);
        CLIENTS.add(result);
        return result;
    }

    @AfterAll
    static void shutdown() { CLIENTS.forEach(RedissonClient::shutdown); }

    HoldRequest request(Long... slots) {
        return new HoldRequest(1L, LocalDate.parse("2030-01-01"), List.of(slots), "0912345678");
    }

    void available() {
        discovery.expect(requestTo("http://discovery/api/public/courts/1/slots?date=2030-01-01"))
                .andRespond(withSuccess("""
                    [{"slotId":1,"status":"AVAILABLE","startTime":"18:00:00"},
                     {"slotId":2,"status":"AVAILABLE","startTime":"18:30:00"}]
                    """, MediaType.APPLICATION_JSON));
    }

    @Test
    void createsHoldWithExactCommonExpiryAndTokenMetadata() throws Exception {
        available();
        var response = mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request(1L, 2L)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresInSeconds").value(300)).andReturn().getResponse();
        var result = MAPPER.readTree(response.getContentAsString());
        String token = result.get("holdToken").asText();
        String key = "fcourt:hold:1:2030-01-01:1";
        assertThat(redis.<String>getBucket(key, StringCodec.INSTANCE).get()).isEqualTo(token);
        long ttl = redis.getBucket(key).remainTimeToLive();
        assertThat(ttl).isBetween(295_000L, 300_000L);
        String script = "return redis.call('pexpiretime',KEYS[1])";
        Long expiry = redis.getScript().eval(org.redisson.api.RScript.Mode.READ_ONLY, script,
                org.redisson.api.RScript.ReturnType.INTEGER, List.of(key));
        assertThat(Instant.ofEpochMilli(expiry)).isEqualTo(Instant.parse(result.get("expiresAt").asText()));
        Long tokenExpiry = redis.getScript().eval(org.redisson.api.RScript.Mode.READ_ONLY, script,
                org.redisson.api.RScript.ReturnType.INTEGER, List.of("fcourt:hold-token:" + token));
        assertThat(tokenExpiry).isEqualTo(expiry);
        assertThat(redis.<String>getBucket("fcourt:hold-token:" + token, StringCodec.INSTANCE).get())
                .contains("0912345678");
        discovery.verify();
    }

    @Test
    void onlyOneOfConcurrentClientsWins() throws Exception {
        var second = new RedisHoldStore(client(), MAPPER);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var gate = new CountDownLatch(1);
            List<Callable<Instant>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                var target = i % 2 == 0 ? store : second;
                String token = "contender-" + i;
                attempts.add(() -> { gate.await(); return target.acquire(request(1L, 2L), token); });
            }
            var futures = attempts.stream().map(pool::submit).toList();
            gate.countDown();
            int winners = 0;
            for (var f : futures) if (f.get() != null) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(redis.getKeys().count()).isEqualTo(3);
        }
    }

    @Test
    void conflictNeverWritesPartialSelectionOrToken() {
        store.acquire(request(2L), "first");
        assertThat(store.acquire(request(1L, 2L), "second")).isNull();
        assertThat(redis.getBucket("fcourt:hold:1:2030-01-01:1").isExists()).isFalse();
        assertThat(redis.getBucket("fcourt:hold-token:second").isExists()).isFalse();
        assertThat(redis.<String>getBucket("fcourt:hold:1:2030-01-01:2", StringCodec.INSTANCE).get()).isEqualTo("first");
    }

    @Test
    void expiredKeyCanBeAcquiredAgainWithoutCleanupJob() {
        store.acquire(request(1L), "first");
        redis.getBucket("fcourt:hold:1:2030-01-01:1").expire(Instant.now().minusSeconds(1));
        assertThat(store.acquire(request(1L), "second")).isNotNull();
    }

    @Test
    void heldSlotReturns409AndPreservesExistingOwner() throws Exception {
        store.acquire(request(1L), "existing");
        available();
        mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request(1L)))).andExpect(status().isConflict());
        assertThat(redis.<String>getBucket("fcourt:hold:1:2030-01-01:1", StringCodec.INSTANCE).get()).isEqualTo("existing");
    }

    @Test
    void unavailableDiscoveryFailsClosed() throws Exception {
        discovery.expect(requestTo("http://discovery/api/public/courts/1/slots?date=2030-01-01"))
                .andRespond(withServerError());
        mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request(1L)))).andExpect(status().isServiceUnavailable());
        assertThat(redis.getKeys().count()).isZero();
    }

    @Test
    void invalidDiscoveryPayloadNeverCreatesHolds() throws Exception {
        for (String body : List.of("null", "{}", "[]", "[{\"slotId\":1}]", "invalid-json")) {
            setup();
            discovery.expect(requestTo("http://discovery/api/public/courts/1/slots?date=2030-01-01"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(request(1L)))).andExpect(status().isServiceUnavailable());
            assertThat(redis.getKeys().count()).isZero();
        }
    }

    @Test
    void pastStartTimeIsRejectedEvenIfDiscoverySaysAvailable() throws Exception {
        discovery.expect(requestTo("http://discovery/api/public/courts/1/slots?date=2030-01-01"))
                .andRespond(withSuccess("[{\"slotId\":1,\"status\":\"AVAILABLE\",\"startTime\":\"06:30:00\"}]",
                        MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request(1L)))).andExpect(status().isConflict());
        assertThat(redis.getKeys().count()).isZero();
    }

    @Test
    void bookedOutOfServiceHeldAndExpiredSlotsAreRejected() throws Exception {
        for (String state : List.of("BOOKED", "OUT_OF_SERVICE", "HOLD", "EXPIRED")) {
            setup();
            discovery.expect(requestTo("http://discovery/api/public/courts/1/slots?date=2030-01-01"))
                    .andRespond(withSuccess("[{\"slotId\":1,\"status\":\"" + state
                            + "\",\"startTime\":\"18:00:00\"}]", MediaType.APPLICATION_JSON));
            mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(request(1L)))).andExpect(status().isConflict());
            assertThat(redis.getKeys().count()).isZero();
        }
    }

    @Test
    void rejectsInvalidRequestsWithoutCallingDiscovery() throws Exception {
        for (String json : List.of("{}", "{\"courtId\":1,\"date\":\"2030-01-01\",\"slotIds\":[1],\"guestPhone\":\"123\"}",
                MAPPER.writeValueAsString(request(1L, 1L)), MAPPER.writeValueAsString(request()))) {
            mvc.perform(post("/api/bookings/hold-slots").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        assertThat(redis.getKeys().count()).isZero();
    }
}
