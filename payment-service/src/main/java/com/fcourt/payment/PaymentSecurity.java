package com.fcourt.payment;

import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class PaymentSecurity {
    @Bean
    JwtDecoder jwtDecoder(@Value("${payment.jwk-set-uri}") String jwks,
            @Value("${payment.jwt-issuer}") String issuer, @Value("${payment.jwt-audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> aud = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid audience",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),aud));
        return decoder;
    }
    @Bean @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object roles=jwt.getClaims().get("roles");
            if (!(roles instanceof Collection<?>)) roles=List.of(jwt.getClaims().getOrDefault("role",""));
            return ((Collection<?>)roles).stream().filter("ADMIN"::equals)
                    .<org.springframework.security.core.GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_ADMIN")).toList();
        });
        return http.securityMatcher("/api/admin/payments/**").csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("ADMIN"))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
    @Bean @Order(2)
    SecurityFilterChain customerSecurity(HttpSecurity http) throws Exception {
        // Customer and guest authorization is delegated to the booking authority per request.
        return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
