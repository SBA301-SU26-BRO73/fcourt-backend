package com.fcourt.reporting.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.reporting.events.EventTranslator.BookingFact;
import com.fcourt.reporting.persistence.ReportQueryStore.RevenueRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Export / overview SQL against real Postgres + the Flyway schema; totals must equal the dashboard's. Needs Docker. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReadModelStore.class, ReportQueryStore.class})
class ReportQueryStoreIntegrationTest {
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

    @Autowired ReadModelStore model;
    @Autowired ReportQueryStore reports;

    void fact(long booking, long branch, String status, int amount, int revenue, LocalDate date) {
        model.apply(new BookingFact("e-" + booking, "T", booking, branch, status, BigDecimal.valueOf(amount), date, 2,
                BigDecimal.valueOf(revenue), revenue > 0 ? Instant.parse("2026-10-01T00:00:00Z") : null,
                17L, "Court A", "Customer A", List.of()));
    }

    @Test
    void exportRowsAreBranchScopedDateBoundedConfirmedOnlyAndMatchTheDashboard() {
        fact(1, 3, "CONFIRMED", 100_000, 100_000, LocalDate.of(2026, 10, 1));
        fact(2, 3, "CONFIRMED", 250_000, 250_000, LocalDate.of(2026, 10, 31));
        fact(3, 3, "CONFIRMED", 70_000, 70_000, LocalDate.of(2026, 11, 1)); // after the range
        fact(4, 4, "CONFIRMED", 900_000, 900_000, LocalDate.of(2026, 10, 10)); // other owner's branch
        fact(5, 3, "PENDING", 50_000, 0, LocalDate.of(2026, 10, 5)); // not confirmed
        fact(6, 3, "CANCELLED", 60_000, 0, LocalDate.of(2026, 10, 6)); // cancelled never counts

        List<RevenueRow> rows = new ArrayList<>();
        reports.revenueRows(List.of(3L), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), rows::add);

        assertThat(rows).extracting(RevenueRow::bookingId).containsExactly(1L, 2L);
        assertThat(rows.stream().map(RevenueRow::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("350000");
        // The dashboard's per-day figure (todayRevenue) is the same rule: CONFIRMED revenue by booking date.
        assertThat(model.revenueOnBookingDate(List.of(3L), LocalDate.of(2026, 10, 31))).isEqualByComparingTo("250000");
        assertThat(rows.get(0).slotCount()).isEqualTo(2);
        assertThat(rows.get(0).courtName()).isEqualTo("Court A");
        assertThat(rows.get(0).customerName()).isEqualTo("Customer A");
        assertThat(rows.get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(rows.get(0).revenueAt()).isNotNull();
    }

    @Test
    void platformAggregatesCoverAllBranchesAndSkipUnconfirmedBookings() {
        fact(11, 21, "CONFIRMED", 100, 100, LocalDate.of(2097, 3, 15));
        fact(12, 22, "CONFIRMED", 200, 200, LocalDate.of(2097, 3, 20));
        fact(13, 22, "CONFIRMED", 400, 400, LocalDate.of(2097, 4, 1));
        fact(14, 23, "CANCELLED", 50, 0, LocalDate.of(2097, 4, 2));
        fact(15, 23, "PENDING", 80, 0, LocalDate.of(2097, 4, 3));

        assertThat(reports.totalRevenue()).isGreaterThanOrEqualTo(new BigDecimal("700"));
        var byBranch = reports.revenueByBranch();
        assertThat(byBranch.get(21L)).isEqualByComparingTo("100");
        assertThat(byBranch.get(22L)).isEqualByComparingTo("600");
        assertThat(byBranch).doesNotContainKey(23L);
        var byMonth = reports.revenueByMonth(LocalDate.of(2097, 1, 1));
        assertThat(byMonth.get("2097-03")).isEqualByComparingTo("300");
        assertThat(byMonth.get("2097-04")).isEqualByComparingTo("400");
    }
}
