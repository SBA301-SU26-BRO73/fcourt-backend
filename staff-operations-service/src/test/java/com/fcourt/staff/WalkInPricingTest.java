package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.staff.integration.AvailabilityGateway.Slot;
import com.fcourt.staff.service.WalkInPricing;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

class WalkInPricingTest {
    @Test
    void computesConfiguredFixedAndPercentageSurchargeFromCanonicalSlots() {
        var pricing = new WalkInPricing(new BigDecimal("1000"), new BigDecimal("10"), "counter-v2");
        var quote = pricing.quote(List.of(slot(38, "18:30", "19:00", "100000"), slot(37, "18:00", "18:30", "100000")));
        assertThat(quote.baseAmount()).isEqualByComparingTo("200000");
        assertThat(quote.surchargeAmount()).isEqualByComparingTo("21000");
        assertThat(quote.totalAmount()).isEqualByComparingTo("221000");
        assertThat(quote.policyVersion()).isEqualTo("counter-v2");
        assertThat(quote.items()).extracting(item -> item.slotId()).containsExactly(37L, 38L);
    }

    @Test
    void roundsOnceToTwoDecimalPlacesAndDefaultsToNoSurcharge() {
        assertThat(new WalkInPricing(BigDecimal.ZERO, new BigDecimal("10"), "v1")
                .quote(List.of(slot(1, "18:00", "18:30", "0.05"))).surchargeAmount()).isEqualByComparingTo("0.01");
        assertThat(new WalkInPricing(BigDecimal.ZERO, BigDecimal.ZERO, "v1")
                .quote(List.of(slot(1, "23:30", "00:00", "100000"))).totalAmount()).isEqualByComparingTo("100000");
    }

    @Test
    void rejectsNonContiguousSelectionAndOverflow() {
        var pricing = new WalkInPricing(BigDecimal.ZERO, BigDecimal.ZERO, "v1");
        assertThatThrownBy(() -> pricing.quote(List.of(slot(1, "18:00", "18:30", "1"), slot(2, "19:00", "19:30", "1"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> pricing.quote(List.of(slot(1, "18:00", "18:30", "100000000"))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "0,101", "0.001,0", "0,1.00001"})
    void invalidPolicyFailsAtStartup(String fixed, String percentage) {
        assertThatThrownBy(() -> new WalkInPricing(new BigDecimal(fixed), new BigDecimal(percentage), "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Slot slot(long id, String start, String end, String price) {
        return new Slot(id, LocalTime.parse(start), LocalTime.parse(end), new BigDecimal(price), "AVAILABLE");
    }
}
