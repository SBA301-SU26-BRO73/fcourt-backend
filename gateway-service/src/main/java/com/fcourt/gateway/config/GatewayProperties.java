package com.fcourt.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway settings (prefix {@code fcourt.gateway}). Path entries use the form {@code "METHOD /pattern"}
 * where METHOD is an HTTP method or {@code ANY}, and the pattern is a Spring {@code PathPattern}.
 */
@ConfigurationProperties("fcourt.gateway")
public record GatewayProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Security security,
        @DefaultValue Swagger swagger,
        @DefaultValue Cors cors) {

    /** Same variables the services use to validate the tokens auth-service issues. */
    public record Jwt(
            @DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwkSetUri,
            @DefaultValue("http://localhost:8081") String issuer,
            @DefaultValue("fcourt") String audience) {}

    /**
     * {@code publicPaths}: reachable without a token. {@code authenticatedPaths}: checked before
     * {@code publicPaths}, carve-outs that need a token inside a broader public pattern.
     * {@code blockedPaths}: never forwarded, answered with 403 before any other processing.
     * {@code localPaths}: served by the gateway itself, exempt from blocking and from the token check.
     */
    public record Security(
            @DefaultValue List<String> publicPaths,
            @DefaultValue List<String> authenticatedPaths,
            @DefaultValue({"/api/internal/**", "/actuator/**"}) List<String> blockedPaths,
            @DefaultValue({"/actuator/health", "/actuator/health/**"}) List<String> localPaths) {}

    /** Aggregated Swagger UI. {@code services}: service names (static discovery instances) whose {@code /v3/api-docs} is listed. */
    public record Swagger(@DefaultValue("true") boolean enabled, @DefaultValue List<String> services) {}

    /** Empty means no CORS handling (clients are same-origin through nginx). */
    public record Cors(@DefaultValue List<String> allowedOrigins) {}
}
