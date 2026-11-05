package com.fcourt.reporting.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account and SaaS figures live in auth-service and subscription-service; reporting never reads their databases.
 * The SUPER_ADMIN's Bearer token is forwarded so each service applies its own authorization.
 * Any failure is surfaced (403/503) rather than replaced by zeros, so the overview never shows invented numbers.
 */
@Component
public class PlatformStatsClient {
    public record OwnerCounts(long active, long pending, long rejected, long inactive, long total) { }

    public record CustomerCounts(long total, long active) { }

    public record MonthCount(String month, long count) { }

    public record AccountStats(OwnerCounts owners, CustomerCounts customers, List<MonthCount> newAccountsByMonth) { }

    public record OwnerName(long id, String fullName, String email) { }

    public record MonthAmount(String month, BigDecimal amount) { }

    public record SaasRevenue(BigDecimal total, long paidPayments, List<MonthAmount> byMonth) { }

    private final RestClient auth;
    private final RestClient subscription;

    public PlatformStatsClient(@Value("${reporting.auth-service-url}") String authUrl,
                               @Value("${reporting.subscription-url}") String subscriptionUrl) {
        this.auth = client(authUrl);
        this.subscription = client(subscriptionUrl);
    }

    private static RestClient client(String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public AccountStats accounts(String bearer, LocalDate from, String timeZone) {
        return call(() -> auth.get().uri("/api/super-admin/stats/accounts?from={from}&timeZone={zone}", from, timeZone)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve().body(AccountStats.class));
    }

    public List<OwnerName> ownerNames(String bearer, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return call(() -> auth.get().uri("/api/super-admin/stats/owner-names?ids={ids}", csv)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve()
                .body(new ParameterizedTypeReference<List<OwnerName>>() { }));
    }

    public SaasRevenue saasRevenue(String bearer, LocalDate from, String timeZone) {
        return call(() -> subscription.get()
                .uri("/api/super-admin/stats/saas-revenue?from={from}&timeZone={zone}", from, timeZone)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve().body(SaasRevenue.class));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) throw unavailable();
            return body;
        } catch (HttpClientErrorException error) {
            int code = error.getStatusCode().value();
            if (code == 401 || code == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform statistics access denied");
            }
            throw unavailable();
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Platform statistics source unavailable");
    }
}
