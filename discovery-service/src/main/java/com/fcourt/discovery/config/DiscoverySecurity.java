package com.fcourt.discovery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DiscoverySecurity {
    @Bean
    JwtDecoder jwtDecoder(@Value("${discovery.auth.jwk-set-uri}") String jwks,
            @Value("${discovery.auth.issuer}") String issuer, @Value("${discovery.auth.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid;
            try { valid = Long.parseLong(jwt.getSubject()) > 0 && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null; }
            catch (RuntimeException error) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain discoveryFilterChain(HttpSecurity http) throws Exception {
        var bearer = new DefaultBearerTokenResolver();
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**", "/actuator/health", "/actuator/info").permitAll()
                        // Service-to-service lock check of facility-service; the X-Internal-Service-Token is verified in the controller.
                        .requestMatchers(HttpMethod.GET, "/api/internal/courts/*/slot-conflicts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/reviews", "/api/reviews/images", "/api/admin/reviews/*/reply").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> request.getRequestURI().startsWith(request.getContextPath() + "/api/public/") ? null : bearer.resolve(request))
                        .jwt(jwt -> {})).build();
    }
}
