package com.fcourt.discovery.repository;

import com.fcourt.discovery.component.SlotSourceClient;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class SlotHoldRepository {
    private final StringRedisTemplate redis;

    public SlotHoldRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Set<Long> heldSlots(long courtId, LocalDate date, List<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        var keys = ids.stream().map(id -> "fcourt:hold:" + courtId + ":" + date + ":" + id).toList();
        try {
            var values = redis.opsForValue().multiGet(keys);
            if (values == null || values.size() != ids.size()) throw SlotSourceClient.unavailable();
            Set<Long> held = new HashSet<>();
            for (int index = 0; index < ids.size(); index++) {
                if (values.get(index) != null) held.add(ids.get(index));
            }
            return held;
        } catch (DataAccessException exception) {
            throw SlotSourceClient.unavailable();
        }
    }
}
