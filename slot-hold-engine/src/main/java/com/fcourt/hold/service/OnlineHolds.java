package com.fcourt.hold.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.hold.dto.request.HoldRequest;
import com.fcourt.hold.dto.request.OnlineHoldClaimRequest;
import com.fcourt.hold.dto.response.OnlineHoldLease;
import com.fcourt.hold.dto.response.OnlineHoldReleaseInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A public hold is a bearer capability; only booking may seal or release it. */
@Service
public class OnlineHolds {
  private final RedissonClient redis;
  private final ObjectMapper json;
  private final byte[] credential;

  public OnlineHolds(
      RedissonClient redis,
      ObjectMapper json,
      @Value("${BOOKING_HOLD_SERVICE_TOKEN:}") String credential) {
    this.redis = redis;
    this.json = json;
    this.credential = credential.getBytes(StandardCharsets.UTF_8);
  }

  public void authorize(String supplied) {
    if (credential.length < 32) throw error(503, "Online hold authority is not configured");
    if (supplied == null
        || !MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8)))
      throw error(403, "Booking service credential required");
  }

  public OnlineHoldLease claim(String supplied, OnlineHoldClaimRequest request) {
    authorize(supplied);
    if (request.slotIds().size() != new HashSet<>(request.slotIds()).size())
      throw error(400, "Duplicate slots");
    String token = request.holdToken().toString();
    String tokenKey = "fcourt:hold-token:" + token;
    String hash = hash(token);
    String sealKey = "fcourt:online-seal:" + hash;
    try {
      String raw = redis.<String>getBucket(tokenKey, StringCodec.INSTANCE).get();
      if (raw == null) throw error(409, "Hold expired or missing");
      HoldRequest hold = json.readValue(raw, HoldRequest.class);
      if (!hold.courtId().equals(request.courtId())
          || !hold.date().equals(request.date())
          || hold.slotIds().size() != request.slotIds().size()
          || !new HashSet<>(hold.slotIds()).equals(new HashSet<>(request.slotIds())))
        throw error(409, "Hold selection mismatch");
      var keys = new ArrayList<Object>();
      for (long slot : hold.slotIds())
        keys.add(CounterHolds.slotKey(hold.courtId(), hold.date(), slot));
      keys.add(tokenKey);
      keys.add(sealKey);
      String identity = request.actorId() + ":" + request.commandKey();
      String release = json.writeValueAsString(new OnlineHoldReleaseInfo(token, hold));
      Number expiry =
          redis
              .getScript(StringCodec.INSTANCE)
              .eval(
                  RScript.Mode.READ_WRITE,
                  """
local metadata=redis.call('get',KEYS[#KEYS-1])
if metadata~=ARGV[2] then return 0 end
local owner=redis.call('get',KEYS[#KEYS])
if owner and owner~=ARGV[3] then return 0 end
local ttl=redis.call('pttl',KEYS[#KEYS-1])
if ttl<30000 then return 0 end
for i=1,#KEYS-2 do if redis.call('get',KEYS[i])~=ARGV[1] then return 0 end end
redis.call('set',KEYS[#KEYS],ARGV[3],'PX',ttl)
redis.call('set',ARGV[4],ARGV[5],'PX',ttl)
local t=redis.call('time');return t[1]*1000+math.floor(t[2]/1000)+ttl
""",
                  RScript.ReturnType.INTEGER,
                  keys,
                  token,
                  raw,
                  identity,
                  "fcourt:online-release:" + hash,
                  release);
      if (expiry == null || expiry.longValue() <= 0)
        throw error(409, "Hold expired, changed or claimed");
      return new OnlineHoldLease(
          hash,
          Instant.ofEpochMilli(expiry.longValue()),
          hold.courtId(),
          hold.date(),
          hold.slotIds(),
          hold.guestPhone());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw error(503, "Online hold authority unavailable");
    }
  }

  public void release(String supplied, String hash) {
    authorize(supplied);
    if (hash == null || !hash.matches("[0-9a-f]{64}")) throw error(400, "Invalid hold reference");
    try {
      String releaseKey = "fcourt:online-release:" + hash;
      String raw = redis.<String>getBucket(releaseKey, StringCodec.INSTANCE).get();
      if (raw == null) return;
      var info = json.readValue(raw, OnlineHoldReleaseInfo.class);
      var keys = new ArrayList<Object>();
      for (long slot : info.hold().slotIds())
        keys.add(CounterHolds.slotKey(info.hold().courtId(), info.hold().date(), slot));
      keys.add("fcourt:hold-token:" + info.token());
      keys.add("fcourt:online-seal:" + hash);
      keys.add(releaseKey);
      redis
          .getScript(StringCodec.INSTANCE)
          .eval(
              RScript.Mode.READ_WRITE,
              """
if redis.call('get',KEYS[#KEYS])~=ARGV[2] then return 0 end
for i=1,#KEYS-3 do if redis.call('get',KEYS[i])==ARGV[1] then redis.call('del',KEYS[i]) end end
redis.call('del',KEYS[#KEYS-2],KEYS[#KEYS-1],KEYS[#KEYS]);return 1
""",
              RScript.ReturnType.INTEGER,
              keys,
              info.token(),
              raw);
    } catch (Exception e) {
      throw error(503, "Online hold release unavailable");
    }
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseStatusException error(int code, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(code), message);
  }
}
