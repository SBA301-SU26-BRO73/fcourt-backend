package com.fcourt.notification.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class DeviceSecurity {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${notification.jwk-set-uri}") String jwks,
            @Value("${notification.jwt-issuer}") String issuer,
            @Value("${notification.jwt-audience}") String audience) {
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
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        authorize ->
                                authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll()
                                .requestMatchers("/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.PUT, "/devices")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.DELETE, "/devices/**")
                                        .authenticated()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}))
                .build();
    }
}
