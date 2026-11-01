package com.fcourt.facility.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** RS256 JWT resource server, same claim rules as the other FCOURT services. */
@Configuration
class FacilitySecurity {
    private static final Set<String> ROLES = Set.of("ADMIN", "STAFF", "SUPER_ADMIN", "CUSTOMER");

    @Bean
    JwtDecoder jwtDecoder(@Value("${facility.auth.jwk-set-uri}") String jwks,
                          @Value("${facility.auth.issuer}") String issuer,
                          @Value("${facility.auth.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid;
            try {
                valid = Long.parseLong(jwt.getSubject()) > 0 && jwt.getAudience() != null
                        && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null;
            } catch (RuntimeException invalidSubject) {
                valid = false;
            }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object value = jwt.getClaims().get("roles");
        Collection<?> roles = value instanceof Collection<?> collection
                ? collection : List.of(jwt.getClaims().getOrDefault("role", ""));
        return roles.stream().map(String::valueOf).filter(ROLES::contains)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(FacilitySecurity::authorities);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // Service-to-service read used by discovery-service; restrict at the network layer.
                        .requestMatchers(HttpMethod.GET, "/api/internal/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/staff/**").hasRole("STAFF")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) ->
                                problem(response, mapper, HttpStatus.UNAUTHORIZED, "Authentication required", request.getRequestURI()))
                        .accessDeniedHandler((request, response, error) ->
                                problem(response, mapper, HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, error) ->
                                problem(response, mapper, HttpStatus.UNAUTHORIZED, "Authentication required", request.getRequestURI()))
                        .accessDeniedHandler((request, response, error) ->
                                problem(response, mapper, HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI())))
                .build();
    }

    private static void problem(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
                                String detail, String path) throws IOException {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(java.net.URI.create(path));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
