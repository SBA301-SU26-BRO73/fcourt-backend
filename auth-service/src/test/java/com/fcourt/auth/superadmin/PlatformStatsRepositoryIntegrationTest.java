package com.fcourt.auth.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** SQL of the SUPER_ADMIN account aggregates against real Postgres + the Flyway schema. Needs Docker. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PlatformStatsRepository.class)
class PlatformStatsRepositoryIntegrationTest {
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

    @Autowired PlatformStatsRepository stats;
    @Autowired JdbcTemplate jdbc;

    void user(String email, String role, String status, String createdAt, boolean deleted) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, role, status, full_name, created_at, deleted_at)
                VALUES (?, 'x', ?, ?, ?, ?::timestamptz, ?)""", email, role, status, "Name " + email, createdAt,
                deleted ? OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC) : null);
    }

    @Test
    void countsOwnersAndCustomersPerStatusIgnoringDeletedAccounts() {
        long active = stats.countByStatus("ADMIN").getOrDefault("ACTIVE", 0L);
        long pending = stats.countByStatus("ADMIN").getOrDefault("PENDING_APPROVAL", 0L);
        long customers = stats.countByStatus("CUSTOMER").getOrDefault("ACTIVE", 0L);
        user("o1@t.vn", "ADMIN", "ACTIVE", "2026-10-02T00:00:00Z", false);
        user("o2@t.vn", "ADMIN", "PENDING_APPROVAL", "2026-10-02T00:00:00Z", false);
        user("o3@t.vn", "ADMIN", "ACTIVE", "2026-10-02T00:00:00Z", true);
        user("c1@t.vn", "CUSTOMER", "ACTIVE", "2026-10-02T00:00:00Z", false);

        assertThat(stats.countByStatus("ADMIN").getOrDefault("ACTIVE", 0L)).isEqualTo(active + 1);
        assertThat(stats.countByStatus("ADMIN").getOrDefault("PENDING_APPROVAL", 0L)).isEqualTo(pending + 1);
        assertThat(stats.countByStatus("CUSTOMER").getOrDefault("ACTIVE", 0L)).isEqualTo(customers + 1);
    }

    @Test
    void groupsNewAccountsByLocalMonthAndExcludesStaffAndDeleted() {
        // 2026-09-30T18:00Z is already 2026-10-01 in UTC+7 -> October.
        user("m1@t.vn", "CUSTOMER", "ACTIVE", "2026-09-30T18:00:00Z", false);
        user("m2@t.vn", "ADMIN", "ACTIVE", "2026-10-15T00:00:00Z", false);
        user("m3@t.vn", "STAFF", "ACTIVE", "2026-10-15T00:00:00Z", false);
        user("m4@t.vn", "CUSTOMER", "ACTIVE", "2026-10-16T00:00:00Z", true);
        user("m5@t.vn", "CUSTOMER", "ACTIVE", "2099-01-05T00:00:00Z", false);

        Map<String, Long> months = stats.newAccountsByMonth(Instant.parse("2099-01-01T00:00:00Z"), "Asia/Ho_Chi_Minh");
        assertThat(months).containsExactly(Map.entry("2099-01", 1L));

        Map<String, Long> october = stats.newAccountsByMonth(Instant.parse("2026-09-30T17:00:00Z"), "Asia/Ho_Chi_Minh");
        assertThat(october.get("2026-10")).isGreaterThanOrEqualTo(2L);
        assertThat(october).doesNotContainKey("2026-09");
    }

    @Test
    void ownerNamesOnlyReturnOwners() {
        user("n1@t.vn", "ADMIN", "ACTIVE", "2026-10-02T00:00:00Z", false);
        user("n2@t.vn", "CUSTOMER", "ACTIVE", "2026-10-02T00:00:00Z", false);
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = 'n1@t.vn'", Long.class);
        long customer = jdbc.queryForObject("SELECT id FROM users WHERE email = 'n2@t.vn'", Long.class);

        var names = stats.ownerNames(List.of(owner, customer));

        assertThat(names).singleElement().satisfies(name -> {
            assertThat(name.id()).isEqualTo(owner);
            assertThat(name.fullName()).isEqualTo("Name n1@t.vn");
        });
        assertThat(stats.ownerNames(List.of())).isEmpty();
    }
}
