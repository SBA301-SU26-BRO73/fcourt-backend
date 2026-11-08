package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.facility.api.PricingDtos.RuleRequest;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PricingServiceValidationTest {
    private static RuleRequest request(String start, String end, String price, Boolean peak, List<Integer> days) {
        return new RuleRequest("Peak", LocalTime.parse(start), LocalTime.parse(end), new BigDecimal(price), peak, days);
    }

    @Test
    void defaultsToEveryDayAndNotPeak() {
        var valid = PricingService.validate(request("17:00", "21:00", "150000", null, null));

        assertThat(valid.days()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(valid.peak()).isFalse();
        assertThat(valid.price()).isEqualByComparingTo("150000.00");
    }

    @Test
    void endBeforeOrEqualToStartIsRejected() {
        assertThatThrownBy(() -> PricingService.validate(request("21:00", "17:00", "1000", true, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("endTime must be after startTime");
        assertThatThrownBy(() -> PricingService.validate(request("17:00", "17:00", "1000", true, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void timesMustBeOnTheThirtyMinuteGrid() {
        assertThatThrownBy(() -> PricingService.validate(request("17:15", "21:00", "1000", true, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("30-minute");
        assertThat(PricingService.validate(request("17:30", "21:30", "1000", true, null)).days()).hasSize(7);
    }

    @Test
    void priceAboveColumnCapacityIsRejected() {
        assertThatThrownBy(() -> PricingService.validate(request("17:00", "21:00", "100000000", true, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("at most");
    }

    @Test
    void daysAreSortedAndDeduplicatedBySet() {
        var valid = PricingService.validate(request("17:00", "21:00", "1000", true, List.of(6, 0)));

        assertThat(valid.days()).containsExactly(0, 6);
    }
}
