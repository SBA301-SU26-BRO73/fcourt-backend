package com.fcourt.auth.config;

import com.fcourt.auth.common.ProblemResponses;
import com.fcourt.auth.user.UserRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security shared by every FCOURT service: RS256 JWT validated
 * against a JWKS, issuer and audience. SCRUM-6 (register/login/token issuing)
 * adds its public routes ({@code /api/auth/**}, {@code /.well-known/jwks.json})
 * to the permitAll list below.
 */
@Configuration
public class SecurityConfiguration {
    /** {@code extraValidators}: service-specific checks, e.g. auth-service refusing tokens older than a password change. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${auth.jwt.jwk-set-uri}") String jwks,
                          @Value("${auth.jwt.issuer}") String issuer,
                          @Value("${auth.jwt.audience}") String audience,
                          ObjectProvider<OAuth2TokenValidator<Jwt>> extraValidators) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> isValidSubjectAndAudience(jwt, audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>(
                List.of(JwtValidators.createDefaultWithIssuer(issuer), claims));
        extraValidators.orderedStream().forEach(validators::add);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

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

    /** Maps the {@code roles} array (or single {@code role} claim) to {@code ROLE_*} authorities. */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object value = jwt.getClaims().get("roles");
        Collection<?> roles = value instanceof Collection<?> collection
                ? collection : List.of(jwt.getClaims().getOrDefault("role", ""));
        return roles.stream().map(String::valueOf).filter(UserRole::isKnown)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::authorities);
        var bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/register/**", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout", "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/api/auth/password/forgot", "/api/auth/password/reset").permitAll()
                        .requestMatchers("/api/auth/profile", "/api/auth/password").authenticated()
                        .requestMatchers("/api/super-admin/**").hasRole(UserRole.SUPER_ADMIN.name())
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/internal/staff-accounts", "/api/internal/staff-accounts/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) -> {
                            // Keeps the RFC 6750 WWW-Authenticate header and adds an RFC 7807 body.
                            bearerEntryPoint.commence(request, response, error);
                            ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED,
                                    "Authentication is required");
                        })
                        .accessDeniedHandler((request, response, error) -> ProblemResponses.write(
                                request, response, HttpStatus.FORBIDDEN, "You are not allowed to perform this action")))
                .build();
    }
}
