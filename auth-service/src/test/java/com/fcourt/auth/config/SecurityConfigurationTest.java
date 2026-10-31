package com.fcourt.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void rolesArrayAndSingleRoleClaimBothMapToAuthorities() {
        assertThat(SecurityConfiguration.authorities(jwt("16", "roles", List.of("SUPER_ADMIN", "UNKNOWN"))))
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SUPER_ADMIN");
        assertThat(SecurityConfiguration.authorities(jwt("1", "role", "ADMIN")))
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(SecurityConfiguration.authorities(jwt("1", "scope", "x"))).isEmpty();
    }

    @Test
    void subjectMustBePositiveNumberAndAudienceMustMatch() {
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(jwt("16", "role", "SUPER_ADMIN"), "fcourt")).isTrue();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(jwt("16", "role", "SUPER_ADMIN"), "other")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(jwt("abc", "role", "SUPER_ADMIN"), "fcourt")).isFalse();
        assertThat(SecurityConfiguration.isValidSubjectAndAudience(jwt("0", "role", "SUPER_ADMIN"), "fcourt")).isFalse();
    }

    static Jwt jwt(String subject, String claim, Object value) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .audience(List.of("fcourt")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim(claim, value).build();
    }
}
