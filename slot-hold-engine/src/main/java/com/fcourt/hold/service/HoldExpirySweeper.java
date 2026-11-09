package com.fcourt.hold.service;

import com.fcourt.hold.repository.RedisHoldStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Announces TTL expiry of online holds (SCRUM-108). Redis expires keys silently, so a lightweight
 * sweeper drains the expiry index written by acquire.lua and publishes HOLD_EXPIRED invalidations.
 * No Redis configuration (keyspace notifications) is required, and it is safe with several instances.
 */
@Component
@ConditionalOnProperty(name = "hold.expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
class HoldExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
    private final RedisHoldStore store;

    HoldExpirySweeper(RedisHoldStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${hold.expiry-sweep-ms:1000}")
    void sweep() {
        try {
            store.sweepExpired();
        } catch (RuntimeException e) {
            // Advisory notification: Redis trouble must never break the engine; clients resync by polling.
            log.warn("Hold expiry sweep failed: {}", e.toString());
        }
    }
}
