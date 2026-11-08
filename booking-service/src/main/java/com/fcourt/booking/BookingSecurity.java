package com.fcourt.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class BookingSecurity {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${booking.jwk-set-uri}") String jwks,
            @Value("${booking.jwt-issuer}") String issuer,
            @Value("${booking.jwt-audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> aud =
                jwt ->
                        jwt.getAudience().contains(audience)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Invalid audience", null));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), aud));
        return decoder;
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain posCharges(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/internal/bookings/*/pos-charges")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/internal/bookings/*/pos-charges")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/api/bookings/{id}")
                                        .permitAll()
                                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                                "/api/internal/bookings/*/voucher-snapshot").permitAll()
                                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                                "/api/internal/bookings/*/payment-freeze").permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/internal/courts/*/slot-occupancy")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        o ->
                                o.bearerTokenResolver(
                                                request ->
                                                        request.getDispatcherType()
                                                                        == jakarta.servlet
                                                                                .DispatcherType
                                                                                .ERROR
                                                                ? null
                                                                : new org.springframework.security
                                                                                .oauth2.server
                                                                                .resource.web
                                                                                .DefaultBearerTokenResolver()
                                                                        .resolve(request))
                                        .jwt(j -> {}))
                .build();
    }
}
