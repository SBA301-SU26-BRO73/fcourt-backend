package com.fcourt.discovery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the generated docs: a global Bearer JWT security scheme. */
@Configuration
public class OpenApiConfig {
    static final String BEARER_JWT = "bearer-jwt";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("FCourt Discovery Service").version("v1")
                        .description("REST API of the FCourt Discovery Service."))
                .components(new Components().addSecuritySchemes(BEARER_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
    }
}
