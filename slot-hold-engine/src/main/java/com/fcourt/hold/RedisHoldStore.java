package com.fcourt.hold;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
class RedisHoldStore {
    static final int TTL_SECONDS = 300;
    private final RedissonClient redis;
    private final String script;
    private final ObjectMapper mapper;

    RedisHoldStore(RedissonClient redis, ObjectMapper mapper) throws IOException {
        this.redis = redis;
        this.mapper = mapper;
        this.script = new ClassPathResource("redis/acquire.lua").getContentAsString(StandardCharsets.UTF_8);
    }

    Instant acquire(HoldRequest request, String token) {
        List<Object> keys = new ArrayList<>();
        for (Long slot : request.slotIds()) {
            keys.add("fcourt:hold:" + request.courtId() + ":" + request.date() + ":" + slot);
        }
        keys.add("fcourt:hold-token:" + token);
        String payload;
        try {
            payload = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize validated hold", e);
        }
        Long expiry = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.INTEGER, keys, token, Integer.toString(TTL_SECONDS), payload);
        return expiry == null || expiry == 0 ? null : Instant.ofEpochMilli(expiry);
    }
}
