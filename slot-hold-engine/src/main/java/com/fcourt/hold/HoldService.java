package com.fcourt.hold;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.redisson.client.RedisException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class HoldService {
    private final DiscoveryGateway discovery;
    private final RedisHoldStore store;

    HoldService(DiscoveryGateway discovery, RedisHoldStore store) {
        this.discovery = discovery;
        this.store = store;
    }

    HoldResponse hold(HoldRequest request) {
        if (new HashSet<>(request.slotIds()).size() != request.slotIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate slot IDs");
        }
        discovery.requireAvailable(request);
        String token = UUID.randomUUID().toString();
        Instant expiry;
        try {
            expiry = store.acquire(request, token);
        } catch (RedisException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Slot hold storage unavailable");
        }
        if (expiry == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A requested slot is already held");
        }
        return new HoldResponse(token, expiry, RedisHoldStore.TTL_SECONDS,
                request.courtId(), request.date(), List.copyOf(request.slotIds()));
    }
}
