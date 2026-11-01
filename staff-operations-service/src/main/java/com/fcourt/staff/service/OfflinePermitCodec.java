package com.fcourt.staff.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OfflinePermitCodec {
    private final byte[] key;
    private final ObjectMapper json;
    @Value("${STAFF_OFFLINE_PREVIOUS_SIGNING_KEYS:}")
    private String previousKeys="";
    public OfflinePermitCodec(@Value("${STAFF_OFFLINE_SIGNING_KEY:}") String key, ObjectMapper json) {
        this.key=key.getBytes(StandardCharsets.UTF_8); this.json=json;
    }
    public String sign(Permit permit) {
        requireKey();
        try {
            String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(permit));
            return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        } catch (Exception error) { throw new IllegalStateException("Cannot issue offline permit"); }
    }
    public Permit verify(String token) {
        requireKey();
        try {
            if(token==null || token.length()>4096) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2)throw new IllegalArgumentException();
            var signature=Base64.getUrlDecoder().decode(parts[1]);
            boolean verified=MessageDigest.isEqual(mac(parts[0]),signature);
            if(!previousKeys.isBlank()) {
                String[] keys=previousKeys.split(",");
                if(keys.length>3)throw new IllegalArgumentException();
                for(String old:keys){
                    byte[] bytes=old.strip().getBytes(StandardCharsets.UTF_8);
                    if(bytes.length<32)throw new IllegalArgumentException();
                    verified|=MessageDigest.isEqual(mac(parts[0],bytes),signature);
                }
            }
            if(!verified)throw new IllegalArgumentException();
            return json.readValue(Base64.getUrlDecoder().decode(parts[0]),Permit.class);
        } catch (Exception error) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid offline permit"); }
    }
    private byte[] mac(String payload) throws Exception {
        return mac(payload,key);
    }
    private byte[] mac(String payload,byte[] signingKey) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));
        return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    }
    private void requireKey() {
        if(key.length<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Offline signing is not configured");
        if(!previousKeys.isBlank()) {
            var keys=previousKeys.split(",");
            if(keys.length>3 || java.util.Arrays.stream(keys).anyMatch(k->k.strip().getBytes(StandardCharsets.UTF_8).length<32)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Offline key rotation is not configured correctly");
            }
        }
    }
    public record Permit(long actorId,long branchId,long bookingId,LocalDate date,String codeHash,
                         Instant validFrom,Instant validUntil,Instant issuedAt) { }
}
