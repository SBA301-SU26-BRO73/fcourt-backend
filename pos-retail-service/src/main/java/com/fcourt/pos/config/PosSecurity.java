package com.fcourt.pos.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class PosSecurity {
    @Bean JwtDecoder decoder(@Value("${pos.jwk-set-uri}") String jwks,
                            @Value("${pos.issuer}") String issuer, @Value("${pos.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> identity = jwt -> {
            boolean valid;
            try { valid = Long.parseLong(jwt.getSubject()) > 0 && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null; }
            catch (RuntimeException invalid) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), identity));
        return decoder;
    }

    @Bean @Order(1)
    SecurityFilterChain internal(HttpSecurity http, @Value("${pos.settlement-token}") String token) throws Exception {
        if (!token.isBlank() && token.length() < 32) throw new IllegalArgumentException("Settlement credential must be at least 32 characters");
        return http.securityMatcher("/api/internal/pos/**").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                              FilterChain chain) throws ServletException, IOException {
                        String header = request.getHeader("Authorization");
                        if (token.isBlank()) { response.setStatus(503); return; }
                        String actual = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
                        if (actual.length() > 4096 || !MessageDigest.isEqual(digest(token), digest(actual))) {
                            response.setStatus(401); return;
                        }
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                "booking-service", null, List.of(new SimpleGrantedAuthority("ROLE_BOOKING_SERVICE"))));
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("BOOKING_SERVICE")).build();
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    @Bean @Order(2)
    SecurityFilterChain business(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object value = jwt.getClaims().get("roles");
            Collection<?> roles = value instanceof Collection<?> collection ? collection : List.of(jwt.getClaims().getOrDefault("role", ""));
            return roles.stream().filter(role -> "ADMIN".equals(role) || "STAFF".equals(role))
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        });
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll().requestMatchers("/api/admin/pos/**").hasRole("ADMIN")
                        .requestMatchers("/api/staff/pos/**").hasRole("STAFF").anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))).build();
    }
}
