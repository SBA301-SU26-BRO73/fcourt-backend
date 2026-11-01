package com.fcourt.facility.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.facility.api.TemplateDtos.ApplyTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.SingleTemplateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TemplateRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void singleTemplateRequiresPositivePrice() {
        var free = new SingleTemplateRequest(1, LocalTime.of(9, 0), LocalTime.of(9, 30), BigDecimal.ZERO);
        var paid = new SingleTemplateRequest(1, LocalTime.of(9, 0), LocalTime.of(9, 30), new BigDecimal("1000"));

        assertThat(validator.validate(free)).hasSize(1);
        assertThat(validator.validate(paid)).isEmpty();
    }

    @Test
    void applyRequestRequiresPositiveExplicitPrice() {
        var free = new ApplyTemplateRequest(null, null, null, BigDecimal.ZERO, null, null);
        var paid = new ApplyTemplateRequest(null, null, null, new BigDecimal("1000"), null, null);
        var derived = new ApplyTemplateRequest(null, null, null, null, null, null);

        assertThat(validator.validate(free)).hasSize(1);
        assertThat(validator.validate(paid)).isEmpty();
        assertThat(validator.validate(derived)).isEmpty();
    }
}
