package com.fcourt.payment;

import java.time.*;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class PaymentStatusCache {
    record Status(long bookingId,String paymentStatus,String rejectReason,Instant updatedAt) {}
    record Entry(Status status,Long owner,String guestHash,long version) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private static final DefaultRedisScript<Long> PUT=new DefaultRedisScript<>("""
      local fence=tonumber(redis.call('GET',KEYS[2]) or '-1')
      if tonumber(ARGV[1]) < fence then return 0 end
      redis.call('SET',KEYS[1],ARGV[2],'EX',5)
      redis.call('SET',KEYS[2],ARGV[1],'EX',86400)
      return 1
      """,Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE=new DefaultRedisScript<>("""
      local fence=tonumber(redis.call('GET',KEYS[2]) or '-1')
      if tonumber(ARGV[1]) >= fence then
        redis.call('SET',KEYS[2],ARGV[1],'EX',86400)
        redis.call('DEL',KEYS[1])
      end
      return 1
      """,Long.class);
    PaymentStatusCache(StringRedisTemplate redis,ObjectMapper json,@Value("${payment.status-cache-enabled:true}") boolean enabled){this.redis=redis;this.json=json;this.enabled=enabled;}
    private List<String> keys(long id){return List.of("payment:status:{"+id+"}","payment:status-version:{"+id+"}");}
    Entry get(long id){
        if(!enabled)return null;
        try{String value=redis.opsForValue().get(keys(id).getFirst());return value==null?null:json.readValue(value,Entry.class);}
        catch(Exception e){return null;}
    }
    void put(Entry entry){
        if(!enabled)return;
        try{redis.execute(PUT,keys(entry.status().bookingId()),Long.toString(entry.version()),json.writeValueAsString(entry));}
        catch(Exception e){/* DB/authority response remains available when Redis is unavailable. */}
    }
    void invalidate(long id,long version){
        if(!enabled)return;
        try{redis.execute(INVALIDATE,keys(id),Long.toString(version));}
        catch(Exception e){/* A stale Redis entry expires in at most five seconds. */}
    }
}
