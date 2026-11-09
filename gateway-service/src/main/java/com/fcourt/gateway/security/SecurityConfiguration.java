package com.fcourt.gateway.security;

import com.fcourt.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Authentication at the edge: a valid RS256 token from auth-service (JWKS, issuer, audience) unless the
 * path is on the public allow-list. Authorization (roles, ownership) stays in the services, which also
 * validate the same token themselves.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        var jwt = properties.jwt();
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwt.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> claims = token -> isValidSubjectAndAudience(token, jwt.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(JwtValidators.createDefaultWithIssuer(jwt.issuer()), claims)));
        return decoder;
    }

    /** Same claim rules as the services: positive numeric subject, expiry present, expected audience. */
    static boolean isValidSubjectAndAudience(Jwt jwt, String audience) {
        if (jwt.getSubject() == null || jwt.getAudience() == null || jwt.getExpiresAt() == null) {
            return false;
        }
        try {
            return Long.parseLong(jwt.getSubject()) > 0 && jwt.getAudience().contains(audience);
        } catch (NumberFormatException nonNumericSubject) {
            return false;
        }
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayProperties properties) {
        var security = properties.security();
        var bearerEntryPoint = new BearerTokenServerAuthenticationEntryPoint();
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        var origins = properties.cors().allowedOrigins();
        if (!origins.isEmpty()) {
            http.cors(cors -> cors.configurationSource(corsSource(origins)));
        }
        http.authorizeExchange(exchange -> {
            exchange.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
            security.localPaths().forEach(path -> exchange.pathMatchers(path).permitAll());
            security.authenticatedPaths().forEach(entry -> exchange.matchers(matcher(entry)).authenticated());
            security.publicPaths().forEach(entry -> exchange.matchers(matcher(entry)).permitAll());
            exchange.anyExchange().authenticated();
        }).oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { })
                .authenticationEntryPoint((exchange, error) -> Mono.defer(() ->
                        // Keeps the RFC 6750 WWW-Authenticate header and adds a problem+json body.
                        bearerEntryPoint.commence(exchange, error)
                                .then(Problems.write(exchange, HttpStatus.UNAUTHORIZED, "Authentication is required")))));
        return http.build();
    }

    /** Parses {@code "METHOD /pattern"}; {@code ANY} (or no method) matches every method. */
    static ServerWebExchangeMatcher matcher(String entry) {
        var parts = entry.trim().split("\\s+", 2);
        String pattern = parts.length == 2 ? parts[1] : parts[0];
        var path = new PathPatternParserServerWebExchangeMatcher(pattern);
        if (parts.length < 2 || "ANY".equalsIgnoreCase(parts[0])) {
            return path;
        }
        var method = HttpMethod.valueOf(parts[0].toUpperCase());
        return exchange -> method.equals(exchange.getRequest().getMethod())
                ? path.matches(exchange) : ServerWebExchangeMatcher.MatchResult.notMatch();
    }

    private static UrlBasedCorsConfigurationSource corsSource(List<String> origins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(new ArrayList<>(origins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
