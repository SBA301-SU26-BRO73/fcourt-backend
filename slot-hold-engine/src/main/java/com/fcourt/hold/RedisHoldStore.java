package com.fcourt.hold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class RedisHoldStore {
    static final int TTL_SECONDS = 300;
    private static final int SWEEP_BATCH = 500;
    private final RedissonClient redis;
    private final String acquireScript;
    private final String releaseScript;
    private final String sweepScript;
    private final ObjectMapper mapper;

    RedisHoldStore(RedissonClient redis, ObjectMapper mapper) throws IOException {
        this.redis = redis;
        this.mapper = mapper;
        this.acquireScript = new ClassPathResource("redis/acquire.lua").getContentAsString(StandardCharsets.UTF_8);
        this.releaseScript = new ClassPathResource("redis/release.lua").getContentAsString(StandardCharsets.UTF_8);
        this.sweepScript = new ClassPathResource("redis/sweep.lua").getContentAsString(StandardCharsets.UTF_8);
    }

    Instant acquire(HoldRequest request, String token) {
        List<Object> keys = keysOf(request, token);
        String payload;
        try {
            payload = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize validated hold", e);
        }
        Long expiry = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, acquireScript,
                RScript.ReturnType.INTEGER, keys, token, Integer.toString(TTL_SECONDS), payload);
        return expiry == null || expiry == 0 ? null : Instant.ofEpochMilli(expiry);
    }

    void release(String token) {
        String payload = redis.<String>getBucket(tokenKey(token), StringCodec.INSTANCE).get();
        if (payload == null) return;
        HoldRequest request;
        try {
            request = mapper.readValue(payload, HoldRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt hold metadata", e);
        }
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, releaseScript,
                RScript.ReturnType.INTEGER, keysOf(request, token), token,
                "fcourt:online-seal:" + OnlineHolds.hash(token));
    }

    /** Announces holds whose TTL passed (HOLD_EXPIRED) and returns how many index entries were drained. */
    long sweepExpired() {
        long total = 0;
        // Each script call handles a bounded batch; keep draining so a burst is not announced minutes late.
        for (int batch = 0; batch < 20; batch++) {
            Long drained = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, sweepScript,
                    RScript.ReturnType.INTEGER, List.<Object>of("fcourt:hold-expiry"), Integer.toString(SWEEP_BATCH));
            long count = drained == null ? 0 : drained;
            total += count;
            if (count < SWEEP_BATCH) break;
        }
        return total;
    }

    private static List<Object> keysOf(HoldRequest request, String token) {
        List<Object> keys = new ArrayList<>();
        for (Long slot : request.slotIds()) {
            keys.add("fcourt:hold:" + request.courtId() + ":" + request.date() + ":" + slot);
        }
        keys.add(tokenKey(token));
        return keys;
    }

    private static String tokenKey(String token) {
        return "fcourt:hold-token:" + token;
    }
}
