package com.fcourt.facility.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure price resolution for a court slot (SCRUM-80 peak rules + SCRUM-82 weekday/weekend).
 *
 * <p>Priority, highest first (the first matching tier decides the price):
 * <ol>
 *   <li>Maintenance / inactive court or branch: not bookable (handled by the callers as {@code outOfService}).
 *       Holidays are not modelled yet (no holiday calendar exists), so that tier is empty.</li>
 *   <li>{@code WEEKEND} rule: a rule limited to Saturday and/or Sunday that covers the slot.</li>
 *   <li>{@code PEAK} rule: a peak rule (any other day set) that covers the slot.</li>
 *   <li>{@code OFF_PEAK} rule: a non-peak rule (any other day set) that covers the slot.</li>
 *   <li>{@code DEFAULT}: the slot's own template price (derived from the court default price).</li>
 * </ol>
 * Rules of the same tier that share a day never overlap in time (rejected on write), so a tier never
 * has two candidates; ties are still broken deterministically by id. Weekend rules always beat
 * weekday rules, even when the weekday rule is the peak one.
 */
public final class PricingEngine {
    /** Day numbers as in time_slot_templates: 0 = Sunday .. 6 = Saturday. */
    public static final Set<Integer> WEEKEND_DAYS = Set.of(0, 6);
    public static final Set<Integer> WEEKDAYS = Set.of(1, 2, 3, 4, 5);

    public enum Source { WEEKEND, PEAK, OFF_PEAK, DEFAULT }

    public enum DayType { WEEKDAY, WEEKEND, ALL }

    public record Rule(long id, String name, LocalTime start, LocalTime end, BigDecimal pricePerHour,
                       boolean peak, Set<Integer> days) {
        public DayType dayType() {
            return PricingEngine.dayType(days);
        }

        boolean covers(int dayOfWeek, LocalTime from, LocalTime to) {
            return days.contains(dayOfWeek) && !from.isBefore(start) && !to.isAfter(end);
        }
    }

    public record Priced(BigDecimal price, Source source, Rule rule) { }

    private PricingEngine() { }

    public static DayType dayType(Set<Integer> days) {
        if (WEEKEND_DAYS.containsAll(days)) return DayType.WEEKEND;
        if (WEEKDAYS.containsAll(days)) return DayType.WEEKDAY;
        return DayType.ALL;
    }

    /** Sunday = 0 .. Saturday = 6, the convention of time_slot_templates. */
    public static int dayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    /** Tier of a rule: lower wins. */
    static int tier(Rule rule) {
        if (rule.dayType() == DayType.WEEKEND) return 0;
        return rule.peak() ? 1 : 2;
    }

    /**
     * True when two rules compete inside the same scope (weekend rules vs. all other rules) for some
     * slot; such rules must not coexist.
     */
    public static boolean conflicts(Rule a, Rule b) {
        // Weekend rules may overlap non-weekend rules: they simply win (see class doc).
        if ((tier(a) == 0) != (tier(b) == 0)) return false;
        boolean sharedDay = a.days().stream().anyMatch(b.days()::contains);
        return sharedDay && a.start().isBefore(b.end()) && b.start().isBefore(a.end());
    }

    /**
     * Price of the block [from, to) on the given weekday. {@code templatePrice} is the default and is
     * returned unchanged when no rule covers the whole block.
     */
    public static Priced price(List<Rule> rules, int dayOfWeek, LocalTime from, LocalTime to, BigDecimal templatePrice) {
        Optional<Rule> winner = rules.stream().filter(rule -> rule.covers(dayOfWeek, from, to))
                .min(Comparator.comparingInt(PricingEngine::tier).thenComparingLong(Rule::id));
        if (winner.isEmpty()) return new Priced(templatePrice, Source.DEFAULT, null);
        Rule rule = winner.get();
        long minutes = Duration.between(from, to).toMinutes();
        BigDecimal amount = rule.pricePerHour().multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        Source source = tier(rule) == 0 ? Source.WEEKEND : rule.peak() ? Source.PEAK : Source.OFF_PEAK;
        return new Priced(amount, source, rule);
    }
}
