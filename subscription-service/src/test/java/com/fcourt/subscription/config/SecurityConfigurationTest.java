package com.fcourt.subscription.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void rolesArrayAndSingleRoleClaimBothMapToAuthorities() {
        assertThat(SecurityConfiguration.authorities(jwt(Map.of("roles", List.of("SUPER_ADMIN", "ADMIN")))))
                .extracting(Object::toString).containsExactly("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(SecurityConfiguration.authorities(jwt(Map.of("role", "CUSTOMER"))))
                .extracting(Object::toString).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void unknownRolesAndMissingClaimsGrantNothing() {
        assertThat(SecurityConfiguration.authorities(jwt(Map.of("roles", List.of("ROOT", "", "ROLE_ADMIN"))))).isEmpty();
        assertThat(SecurityConfiguration.authorities(jwt(Map.of("scope", "read")))).isEmpty();
    }

    @Test
    void subjectMustBePositiveNumberAndAudienceMustMatch() {
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("7", List.of("fcourt")), "fcourt")).isTrue();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("0", List.of("fcourt")), "fcourt")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("-1", List.of("fcourt")), "fcourt")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("admin", List.of("fcourt")), "fcourt")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("7", List.of("other")), "fcourt")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(token("7", null), "fcourt")).isFalse();
    }

    static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims))
                .claim("sub", "7").build();
    }

    static Jwt token(String subject, List<String> audience) {
        var builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (audience != null) {
            builder.audience(audience);
        } else {
            builder.claim("noAudience", true);
        }
        return builder.build();
    }
}
