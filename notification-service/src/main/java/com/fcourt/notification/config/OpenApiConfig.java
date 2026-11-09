package com.fcourt.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String BEARER_JWT = "bearer-jwt";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("FCourt Notification Service").version("v1").description("Notification inbox and push device registration APIs for FCourt."))
                .components(new Components().addSecuritySchemes(BEARER_JWT,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
    }
}
