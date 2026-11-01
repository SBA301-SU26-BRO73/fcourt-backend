package com.fcourt.subscription.billing;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/** How long one payment buys: a calendar month or a calendar year. */
enum BillingCycle {
    MONTHLY, YEARLY;

    /** Case-insensitive and tolerant of surrounding spaces; empty for anything else. */
    static Optional<BillingCycle> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    LocalDate addTo(LocalDate date) {
        return this == YEARLY ? date.plusYears(1) : date.plusMonths(1);
    }
}
