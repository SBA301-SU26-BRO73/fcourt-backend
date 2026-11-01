package com.fcourt.payment;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtVerificationTest {
    @Test
    void decoderVerifiesSignatureIssuerAudienceAndExpiry() throws Exception {
        var key=new RSAKeyGenerator(2048).keyID("auth-key").generate();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange -> {
            byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var decoder=new PaymentSecurity().jwtDecoder("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks","fcourt-auth","fcourt");
            assertThat(decoder.decode(token(key,"fcourt-auth","fcourt",Instant.now().plusSeconds(300))).getSubject()).isEqualTo("9");
            for(String invalid:List.of(token(key,"wrong-issuer","fcourt",Instant.now().plusSeconds(300)),
                    token(key,"fcourt-auth","wrong-audience",Instant.now().plusSeconds(300)),
                    token(key,"fcourt-auth","fcourt",Instant.now().minusSeconds(120)),
                    token(new RSAKeyGenerator(2048).keyID("auth-key").generate(),"fcourt-auth","fcourt",Instant.now().plusSeconds(300)))) {
                assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            }
        } finally { server.stop(0); }
    }
    String token(RSAKey key,String issuer,String audience,Instant expiry) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder().subject("9").issuer(issuer).audience(audience).expirationTime(Date.from(expiry)).claim("roles",List.of("ADMIN")).build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
}
