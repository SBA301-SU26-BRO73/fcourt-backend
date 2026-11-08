package com.fcourt.facility.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.facility.api.PricingDtos.RuleRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RuleRequest request(String name, BigDecimal price, List<Integer> days) {
        return new RuleRequest(name, LocalTime.of(17, 0), LocalTime.of(21, 0), price, true, days);
    }

    @Test
    void validRequestPasses() {
        assertThat(validator.validate(request("Peak", new BigDecimal("150000"), List.of(1, 2)))).isEmpty();
        assertThat(validator.validate(request("Peak", new BigDecimal("150000"), null))).isEmpty();
    }

    @Test
    void nameAndPositivePriceAreRequired() {
        assertThat(validator.validate(request(" ", new BigDecimal("150000"), null))).hasSize(1);
        assertThat(validator.validate(request("Peak", BigDecimal.ZERO, null))).hasSize(1);
        assertThat(validator.validate(request("Peak", null, null))).hasSize(1);
    }

    @Test
    void daysMustBeWithinRangeNonEmptyAndUnique() {
        assertThat(validator.validate(request("Peak", BigDecimal.TEN, List.of(7)))).isNotEmpty();
        assertThat(validator.validate(request("Peak", BigDecimal.TEN, List.of()))).isNotEmpty();
        assertThat(validator.validate(request("Peak", BigDecimal.TEN, List.of(1, 1)))).isNotEmpty();
    }
}
