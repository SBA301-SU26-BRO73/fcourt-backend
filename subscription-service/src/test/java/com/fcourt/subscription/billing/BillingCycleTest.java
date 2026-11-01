package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BillingCycleTest {
    @Test
    void parseIsCaseInsensitiveAndTrimsSpaces() {
        assertThat(BillingCycle.parse("monthly")).contains(BillingCycle.MONTHLY);
        assertThat(BillingCycle.parse(" Yearly ")).contains(BillingCycle.YEARLY);
    }

    @Test
    void parseRejectsAnythingElse() {
        assertThat(BillingCycle.parse(null)).isEmpty();
        assertThat(BillingCycle.parse("")).isEmpty();
        assertThat(BillingCycle.parse("weekly")).isEmpty();
    }

    @Test
    void aMonthlyCycleAddsACalendarMonthAndClampsToTheEndOfShortMonths() {
        assertThat(BillingCycle.MONTHLY.addTo(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(BillingCycle.MONTHLY.addTo(LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void aYearlyCycleAddsACalendarYear() {
        assertThat(BillingCycle.YEARLY.addTo(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2027, 11, 1));
        assertThat(BillingCycle.YEARLY.addTo(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2025, 2, 28));
    }
}
