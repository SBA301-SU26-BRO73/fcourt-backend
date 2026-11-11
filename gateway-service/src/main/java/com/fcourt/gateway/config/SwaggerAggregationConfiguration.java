package com.fcourt.gateway.config;

import java.util.LinkedHashSet;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aggregated Swagger UI: for every service in {@code fcourt.gateway.swagger.services} the gateway exposes
 * {@code /docs/<service>/v3/api-docs} (forwarded to {@code lb://<service>/v3/api-docs}) and lists it in the UI
 * dropdown. A service that has no springdoc yet simply answers 404 for its entry.
 */
@Configuration
@ConditionalOnProperty(name = "fcourt.gateway.swagger.enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerAggregationConfiguration {

    @Bean
    RouteLocator swaggerDocsRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        var routes = builder.routes();
        for (String service : properties.swagger().services()) {
            routes.route("docs-" + service, route -> route
                    .order(5)
                    .path("/docs/" + service + "/v3/api-docs")
                    .and().method("GET")
                    .filters(filters -> filters.rewritePath("/docs/" + service + "/v3/api-docs", "/v3/api-docs"))
                    .uri("lb://" + service));
        }
        return routes.build();
    }

    @Bean
    SwaggerUiUrls swaggerUiUrls(SwaggerUiConfigProperties swaggerUi, GatewayProperties properties) {
        var urls = new LinkedHashSet<SwaggerUrl>();
        for (String service : properties.swagger().services()) {
            urls.add(new SwaggerUrl(service, "/docs/" + service + "/v3/api-docs", service));
        }
        swaggerUi.setUrls(urls);
        return new SwaggerUiUrls(urls.size());
    }

    /** Marker bean so the wiring is observable in tests. */
    public record SwaggerUiUrls(int count) {}
}
