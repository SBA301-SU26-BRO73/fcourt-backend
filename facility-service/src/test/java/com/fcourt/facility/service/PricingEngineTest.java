package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.facility.service.PricingEngine.DayType;
import com.fcourt.facility.service.PricingEngine.Priced;
import com.fcourt.facility.service.PricingEngine.Rule;
import com.fcourt.facility.service.PricingEngine.Source;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingEngineTest {
    private static final BigDecimal DEFAULT_SLOT = new BigDecimal("50000.00");
    private static final Set<Integer> ALL = Set.of(0, 1, 2, 3, 4, 5, 6);
    private static final int MONDAY = 1;
    private static final int SATURDAY = 6;
    private static final int SUNDAY = 0;

    private static LocalTime t(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private static Rule rule(long id, String name, int from, int to, String perHour, boolean peak, Set<Integer> days) {
        return new Rule(id, name, t(from, 0), t(to, 0), new BigDecimal(perHour), peak, days);
    }

    private final Rule peak = rule(1, "Peak", 17, 21, "150000", true, Set.of(1, 2, 3, 4, 5));
    private final Rule offPeak = rule(2, "Day", 6, 17, "100000", false, Set.of(1, 2, 3, 4, 5));
    private final Rule weekend = rule(3, "Weekend", 6, 22, "200000", false, Set.of(0, 6));

    private Priced at(List<Rule> rules, int day, int hour, int minute) {
        return PricingEngine.price(rules, day, t(hour, minute), t(hour, minute).plusMinutes(30), DEFAULT_SLOT);
    }

    @Test
    void peakAndOffPeakUseTheirOwnHourlyPriceForAHalfHourSlot() {
        List<Rule> rules = List.of(peak, offPeak);

        assertThat(at(rules, MONDAY, 10, 0).price()).isEqualByComparingTo("50000");
        assertThat(at(rules, MONDAY, 10, 0).source()).isEqualTo(Source.OFF_PEAK);
        assertThat(at(rules, MONDAY, 18, 0).price()).isEqualByComparingTo("75000");
        assertThat(at(rules, MONDAY, 18, 0).source()).isEqualTo(Source.PEAK);
    }

    @Test
    void slotsOutsideEveryRuleKeepTheTemplateDefaultPrice() {
        Priced late = at(List.of(peak, offPeak), MONDAY, 21, 0);

        assertThat(late.source()).isEqualTo(Source.DEFAULT);
        assertThat(late.rule()).isNull();
        assertThat(late.price()).isEqualByComparingTo(DEFAULT_SLOT);
    }

    @Test
    void ruleBoundariesAreHalfOpen() {
        List<Rule> rules = List.of(peak, offPeak);

        assertThat(at(rules, MONDAY, 16, 30).source()).isEqualTo(Source.OFF_PEAK);
        assertThat(at(rules, MONDAY, 17, 0).source()).isEqualTo(Source.PEAK);
        assertThat(at(rules, MONDAY, 20, 30).source()).isEqualTo(Source.PEAK);
        assertThat(at(rules, MONDAY, 21, 0).source()).isEqualTo(Source.DEFAULT);
    }

    @Test
    void weekendRuleBeatsTheWeekdayPeakRuleThatSharesTheDayAndTime() {
        Rule everyDayPeak = rule(4, "Evening", 17, 21, "150000", true, ALL);
        List<Rule> rules = List.of(everyDayPeak, weekend);

        Priced saturdayEvening = at(rules, SATURDAY, 18, 0);
        assertThat(saturdayEvening.source()).isEqualTo(Source.WEEKEND);
        assertThat(saturdayEvening.price()).isEqualByComparingTo("100000");
        assertThat(at(rules, SUNDAY, 18, 0).source()).isEqualTo(Source.WEEKEND);
        assertThat(at(rules, MONDAY, 18, 0).source()).isEqualTo(Source.PEAK);
    }

    @Test
    void weekendRuleDoesNotApplyOnWeekdaysAndWeekdayRulesDoNotApplyOnWeekends() {
        List<Rule> rules = List.of(peak, offPeak, weekend);

        assertThat(at(rules, MONDAY, 10, 0).source()).isEqualTo(Source.OFF_PEAK);
        assertThat(at(rules, SATURDAY, 10, 0).source()).isEqualTo(Source.WEEKEND);
        assertThat(at(List.of(peak, offPeak), SATURDAY, 18, 0).source()).isEqualTo(Source.DEFAULT);
    }

    @Test
    void peakBeatsOffPeakWhenBothCoverTheSlot() {
        Rule wholeDay = rule(5, "All day", 6, 22, "100000", false, ALL);
        List<Rule> rules = List.of(wholeDay, peak);

        assertThat(at(rules, MONDAY, 18, 0).source()).isEqualTo(Source.PEAK);
        assertThat(at(rules, MONDAY, 8, 0).source()).isEqualTo(Source.OFF_PEAK);
    }

    @Test
    void aSlotNotFullyInsideARuleIsNotPricedByIt() {
        Rule partial = rule(6, "Partial", 17, 21, "150000", true, ALL);

        Priced straddling = PricingEngine.price(List.of(partial), MONDAY, t(16, 45), t(17, 15), DEFAULT_SLOT);

        assertThat(straddling.source()).isEqualTo(Source.DEFAULT);
    }

    @Test
    void identicalCompetingRulesResolveDeterministicallyByLowestId() {
        Rule first = rule(7, "A", 17, 21, "120000", true, ALL);
        Rule second = rule(8, "B", 17, 21, "180000", true, ALL);

        assertThat(at(List.of(second, first), MONDAY, 18, 0).rule().id()).isEqualTo(7);
    }

    @Test
    void priceIsRoundedToTwoDecimals() {
        Rule odd = rule(9, "Odd", 6, 22, "100001", false, ALL);

        assertThat(at(List.of(odd), MONDAY, 8, 0).price()).isEqualByComparingTo("50000.50");
    }

    @Test
    void bookingAcrossTheOffPeakToPeakBoundarySumsEachSlotAtItsOwnPrice() {
        List<Rule> rules = List.of(rule(10, "Normal", 6, 17, "100000", false, Set.of(1, 2, 3, 4, 5)),
                rule(11, "Peak", 17, 21, "150000", true, Set.of(1, 2, 3, 4, 5)));

        // 16:00-18:00 = 2 off-peak half hours (50k each) + 2 peak half hours (75k each) = 250k
        BigDecimal total = BigDecimal.ZERO;
        for (LocalTime from = t(16, 0); from.isBefore(t(18, 0)); from = from.plusMinutes(30)) {
            total = total.add(PricingEngine.price(rules, MONDAY, from, from.plusMinutes(30), DEFAULT_SLOT).price());
        }
        assertThat(total).isEqualByComparingTo("250000");
    }

    @Test
    void dayTypeIsDerivedFromTheDaySet() {
        assertThat(PricingEngine.dayType(Set.of(0, 6))).isEqualTo(DayType.WEEKEND);
        assertThat(PricingEngine.dayType(Set.of(6))).isEqualTo(DayType.WEEKEND);
        assertThat(PricingEngine.dayType(Set.of(1, 2, 3, 4, 5))).isEqualTo(DayType.WEEKDAY);
        assertThat(PricingEngine.dayType(Set.of(5, 6))).isEqualTo(DayType.ALL);
        assertThat(PricingEngine.dayType(ALL)).isEqualTo(DayType.ALL);
    }

    @Test
    void dayOfWeekUsesSundayZero() {
        assertThat(PricingEngine.dayOfWeek(LocalDate.of(2026, 11, 8))).isEqualTo(0); // Sunday
        assertThat(PricingEngine.dayOfWeek(LocalDate.of(2026, 11, 9))).isEqualTo(1); // Monday
        assertThat(PricingEngine.dayOfWeek(LocalDate.of(2026, 11, 14))).isEqualTo(6); // Saturday
    }

    @Test
    void conflictsOnlyWithinTheSameScopeOnASharedDay() {
        Rule weekdayPeak = rule(20, "P", 17, 21, "1", true, Set.of(1, 2, 3, 4, 5));
        Rule weekdayOverlap = rule(21, "O", 20, 22, "1", false, Set.of(5));
        Rule mondayOnly = rule(22, "D", 17, 21, "1", true, Set.of(1));
        Rule adjacent = rule(23, "A", 21, 22, "1", false, Set.of(1, 2, 3, 4, 5));
        Rule weekendSame = rule(24, "W", 17, 21, "1", false, Set.of(0, 6));
        Rule weekendOther = rule(25, "W2", 20, 23, "1", false, Set.of(6));

        assertThat(PricingEngine.conflicts(weekdayPeak, weekdayOverlap)).isTrue();
        assertThat(PricingEngine.conflicts(weekdayPeak, mondayOnly)).isTrue();
        assertThat(PricingEngine.conflicts(weekdayPeak, adjacent)).isFalse();
        assertThat(PricingEngine.conflicts(weekdayPeak, weekendSame)).isFalse();
        assertThat(PricingEngine.conflicts(weekendSame, weekendOther)).isTrue();
        assertThat(PricingEngine.conflicts(weekendOther, weekdayOverlap)).isFalse();
    }
}
