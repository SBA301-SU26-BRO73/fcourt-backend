package com.fcourt.subscription.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.subscription.common.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** SQL of the SaaS revenue aggregate against real Postgres + the Flyway schema. Needs Docker. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SaasRevenueStats.class)
class SaasRevenueStatsIntegrationTest {
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired SaasRevenueStats stats;
    @Autowired JdbcTemplate jdbc;

    void payment(long orderCode, String status, int amount, String paidAt) {
        long plan = jdbc.queryForObject("SELECT min(id) FROM subscription_plans", Long.class);
        jdbc.update("""
                INSERT INTO subscription_payments (order_code, admin_id, plan_id, plan_name, billing_cycle, amount,
                    status, paid_at, expired_at)
                VALUES (?, 7, ?, 'Pro', 'MONTHLY', ?, ?, ?::timestamptz, now() + interval '1 day')""",
                orderCode, plan, amount, status, paidAt);
    }

    @Test
    void sumsOnlyPaidPaymentsAndGroupsThemByLocalMonth() {
        var before = stats.revenue(LocalDate.of(2099, 1, 1), "Asia/Ho_Chi_Minh");
        payment(9_000_001, "PAID", 500_000, "2099-01-31T18:00:00Z"); // 2099-02-01 in UTC+7
        payment(9_000_002, "PAID", 300_000, "2099-01-10T00:00:00Z");
        payment(9_000_003, "PENDING", 999_999, null);
        payment(9_000_004, "EXPIRED", 999_999, null);
        payment(9_000_005, "FAILED", 999_999, null);

        var after = stats.revenue(LocalDate.of(2099, 1, 1), "Asia/Ho_Chi_Minh");

        assertThat(after.total().subtract(before.total())).isEqualByComparingTo("800000");
        assertThat(after.paidPayments() - before.paidPayments()).isEqualTo(2);
        assertThat(after.byMonth()).extracting(m -> m.month()).containsExactly("2099-01", "2099-02");
        assertThat(after.byMonth().get(0).amount()).isEqualByComparingTo("300000");
        assertThat(after.byMonth().get(1).amount()).isEqualByComparingTo("500000");
    }

    @Test
    void invalidTimeZoneIsABadRequest() {
        assertThatThrownBy(() -> stats.revenue(LocalDate.of(2099, 1, 1), "Mars/Base"))
                .isInstanceOf(ApiException.class);
    }
}
