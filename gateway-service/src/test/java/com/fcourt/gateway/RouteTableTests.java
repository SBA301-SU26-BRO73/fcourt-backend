package com.fcourt.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

/** Route table of the gateway: which service a public path goes to, with the precedence the Ingress used. */
@SpringBootTest
@ActiveProfiles("test")
class RouteTableTests {
    @Autowired
    RouteLocator routeLocator;

    List<Route> routes;

    @BeforeEach
    void loadRoutes() {
        routes = routeLocator.getRoutes().collectList().block().stream()
                .sorted(Comparator.comparingInt(Route::getOrder)).toList();
    }

    /** First route (by order, then position) whose predicate matches, as the handler mapping picks it. */
    String serviceFor(HttpMethod method, String path) {
        for (Route route : routes) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
            if (Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block())) {
                return route.getUri().toString();
            }
        }
        return null;
    }

    static Stream<Arguments> publicPaths() {
        return Stream.of(
                // precedence: specific before broad
                Arguments.of("/api/bookings/hold-slots", "slot-hold-engine"),
                Arguments.of("/api/bookings/hold-slots/6f1a", "slot-hold-engine"),
                Arguments.of("/api/public/bookings/lookup", "booking-service"),
                Arguments.of("/api/bookings", "booking-service"),
                Arguments.of("/api/bookings/42", "booking-service"),
                Arguments.of("/api/bookings/my", "booking-service"),
                Arguments.of("/api/bookings/42/cancel", "booking-service"),
                Arguments.of("/api/public/branches", "discovery-service"),
                Arguments.of("/api/public/branches/", "discovery-service"),
                Arguments.of("/api/public/branches/7/reviews", "discovery-service"),
                Arguments.of("/api/public/branches/7", "facility-service"),
                Arguments.of("/api/public/branches/7/images", "facility-service"),
                Arguments.of("/api/public/branches/7/courts", "facility-service"),
                Arguments.of("/api/public/courts", "discovery-service"),
                // auth-service
                Arguments.of("/api/auth/login", "auth-service"),
                Arguments.of("/api/auth/me", "auth-service"),
                Arguments.of("/api/auth/profile/avatar", "auth-service"),
                Arguments.of("/api/auth/password/forgot", "auth-service"),
                Arguments.of("/api/super-admin/court-admins", "auth-service"),
                Arguments.of("/api/super-admin/users/3", "auth-service"),
                Arguments.of("/api/super-admin/stats/accounts", "auth-service"),
                Arguments.of("/api/super-admin/stats/owner-names", "auth-service"),
                // subscription-service
                Arguments.of("/api/subscriptions/payment-webhook", "subscription-service"),
                Arguments.of("/api/subscription-plans/all", "subscription-service"),
                Arguments.of("/api/court-types/2", "subscription-service"),
                Arguments.of("/api/super-admin/stats/saas-revenue", "subscription-service"),
                // facility-service
                Arguments.of("/api/admin/branches", "facility-service"),
                Arguments.of("/api/admin/courts/5", "facility-service"),
                Arguments.of("/api/admin/time-slot-templates", "facility-service"),
                Arguments.of("/api/staff/branches", "facility-service"),
                Arguments.of("/api/staff/courts", "facility-service"),
                // reporting-service
                Arguments.of("/api/admin/dashboard/summary", "reporting-service"),
                Arguments.of("/api/super-admin/dashboard", "reporting-service"),
                // discovery-service
                Arguments.of("/api/reviews", "discovery-service"),
                Arguments.of("/api/admin/reviews/3/reply", "discovery-service"),
                // voucher-service
                Arguments.of("/api/vouchers/apply", "voucher-service"),
                Arguments.of("/api/admin/vouchers", "voucher-service"),
                // booking-service
                Arguments.of("/api/admin/bookings", "booking-service"),
                Arguments.of("/api/admin/bookings/full-court", "booking-service"),
                // payment-service
                Arguments.of("/api/payments/my-transactions", "payment-service"),
                Arguments.of("/api/payments/9/status", "payment-service"),
                Arguments.of("/api/refunds/request", "payment-service"),
                Arguments.of("/api/admin/payments/pending", "payment-service"),
                Arguments.of("/api/admin/refunds/pending", "payment-service"),
                // staff-operations-service
                Arguments.of("/api/staff/schedule", "staff-operations-service"),
                Arguments.of("/api/staff/check-in", "staff-operations-service"),
                Arguments.of("/api/staff/offline/tickets", "staff-operations-service"),
                Arguments.of("/api/staff/me", "staff-operations-service"),
                Arguments.of("/api/staff/walk-in", "staff-operations-service"),
                Arguments.of("/api/staff/bookings", "staff-operations-service"),
                Arguments.of("/api/admin/staff", "staff-operations-service"),
                // notification-service
                Arguments.of("/devices", "notification-service"),
                Arguments.of("/devices/abc", "notification-service"),
                // pos-retail-service
                Arguments.of("/api/staff/pos/orders", "pos-retail-service"),
                Arguments.of("/api/admin/pos/products", "pos-retail-service"),
                // WebSocket
                Arguments.of("/ws-fcourt", "ws://booking-service"),
                Arguments.of("/ws-fcourt/info", "ws://booking-service"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("publicPaths")
    void routesToTheOwningService(String path, String service) {
        assertThat(serviceFor(HttpMethod.GET, path)).isEqualTo("lb:" + (service.startsWith("ws:") ? "" : "//") + service);
    }

    @ParameterizedTest
    @MethodSource("neverRouted")
    void neverRoutesInternalAndManagementPaths(String path) {
        assertThat(serviceFor(HttpMethod.GET, path)).isNull();
        assertThat(serviceFor(HttpMethod.POST, path)).isNull();
    }

    static Stream<String> neverRouted() {
        return Stream.of("/api/internal/courts/1/slot-occupancy", "/api/internal/staff-accounts",
                "/api/internal/bookings/1/pos-charges", "/actuator/health", "/actuator/env",
                "/.well-known/jwks.json", "/api/unknown", "/");
    }

    @Test
    void everyServiceHasAtLeastOneRoute() {
        var targets = routes.stream().map(route -> route.getUri().toString()).distinct().toList();
        assertThat(targets).contains("lb://auth-service", "lb://subscription-service", "lb://facility-service",
                "lb://reporting-service", "lb://discovery-service", "lb://voucher-service", "lb://slot-hold-engine",
                "lb://booking-service", "lb://payment-service", "lb://staff-operations-service",
                "lb://notification-service", "lb://pos-retail-service");
    }

    @Test
    void swaggerDocsAreForwardedPerService() {
        assertThat(serviceFor(HttpMethod.GET, "/docs/booking-service/v3/api-docs")).isEqualTo("lb://booking-service");
        assertThat(serviceFor(HttpMethod.POST, "/docs/booking-service/v3/api-docs")).isNull();
    }
}
