package com.fcourt.hold;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
class CounterHolds {
    private final RedissonClient redis;
    private final ObjectMapper json;
    private final HoldStaffIdentity identity;
    private final byte[] credential;

    CounterHolds(
            RedissonClient redis,
            ObjectMapper json,
            HoldStaffIdentity identity,
            @Value("${BOOKING_HOLD_SERVICE_TOKEN:}") String credential) {
        this.redis = redis;
        this.json = json;
        this.identity = identity;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
    }

    static String token(String key) {
        try {
            return "counter-"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String slotKey(long court, LocalDate date, long slot) {
        return "fcourt:hold:" + court + ":" + date + ":" + slot;
    }

    Lease reserve(String bearer, String serviceToken, Request request) {
        if (credential.length < 32) throw HoldStaffIdentity.unavailable();
        if (serviceToken == null
                || !MessageDigest.isEqual(
                        credential, serviceToken.getBytes(StandardCharsets.UTF_8)))
            throw HoldStaffIdentity.denied();
        identity.require(bearer, request.branchId(), request.actorId());
        if (new HashSet<>(request.slotIds()).size() != request.slotIds().size()
                || !request.commandKey().startsWith("staff:walk-in:" + request.actorId() + ":"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid counter selection");
        try {
            UUID.fromString(
                    request.commandKey().substring(request.commandKey().lastIndexOf(':') + 1));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid counter command");
        }
        String token = token(request.commandKey());
        var keys = new ArrayList<Object>();
        for (long slot : request.slotIds())
            keys.add(slotKey(request.courtId(), request.date(), slot));
        keys.add("fcourt:hold-token:" + token);
        try {
            String fingerprint = json.writeValueAsString(request);
            Number expiry =
                    redis.getScript(StringCodec.INSTANCE)
                            .eval(
                                    RScript.Mode.READ_WRITE,
                                    """
local previous=redis.call('get',KEYS[#KEYS])
if previous and previous~=ARGV[2] then return -1 end
for i=1,#KEYS-1 do
  local value=redis.call('get',KEYS[i])
  if value and value~=ARGV[1] then return 0 end
end
local t=redis.call('time');local now=t[1]*1000+math.floor(t[2]/1000)
local expiry=now+300000
if previous then expiry=now+redis.call('pttl',KEYS[#KEYS]) end
if expiry-now<30000 then return 0 end
for i=1,#KEYS-1 do redis.call('set',KEYS[i],ARGV[1],'PXAT',expiry) end
redis.call('set',KEYS[#KEYS],ARGV[2],'PXAT',expiry)
return expiry
""",
                                    RScript.ReturnType.INTEGER,
                                    keys,
                                    token,
                                    fingerprint);
            if (expiry == null || expiry.longValue() <= 0)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Selected slot is held or command changed");
            return new Lease(
                    token,
                    Instant.ofEpochMilli(expiry.longValue()),
                    request.courtId(),
                    request.date(),
                    request.slotIds());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Hold authority unavailable");
        }
    }

    List<Long> busy(long court, LocalDate date, List<Long> slots, String command) {
        try {
            List<Object> keys =
                    slots.stream().map(id -> (Object) slotKey(court, date, id)).toList();
            List<Long> flags =
                    redis.getScript(StringCodec.INSTANCE)
                            .eval(
                                    RScript.Mode.READ_ONLY,
                                    "local r={};for i=1,#KEYS do local"
                                        + " v=redis.call('get',KEYS[i]);r[i]=(v and v~=ARGV[1]) and"
                                        + " 1 or 0 end;return r",
                                    RScript.ReturnType.MULTI,
                                    keys,
                                    token(command));
            var busy = new ArrayList<Long>();
            for (int n = 0; n < slots.size(); n++) if (flags.get(n) != 0) busy.add(slots.get(n));
            return busy;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Hold authority unavailable");
        }
    }

    record Request(
            @Positive long branchId,
            @Positive long actorId,
            @Positive long courtId,
            @NotNull LocalDate date,
            @NotEmpty @Size(max = 48) List<@Positive Long> slotIds,
            @NotBlank @Size(max = 128) String commandKey) {}

    record Lease(
            String token, Instant expiresAt, long courtId, LocalDate date, List<Long> slotIds) {}
}

@RestController
class CounterHoldController {
    private final CounterHolds holds;

    CounterHoldController(CounterHolds holds) {
        this.holds = holds;
    }

    @PostMapping("/api/internal/counter-holds")
    ResponseEntity<CounterHolds.Lease> reserve(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @RequestHeader(value = "X-Booking-Service-Token", required = false) String credential,
            @Valid @RequestBody CounterHolds.Request request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(holds.reserve(bearer, credential, request));
    }
}
