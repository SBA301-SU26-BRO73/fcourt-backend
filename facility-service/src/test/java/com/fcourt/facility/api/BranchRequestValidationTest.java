package com.fcourt.facility.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BranchRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    static BranchRequest request(LocalTime open, LocalTime close, String bin, BigDecimal lat, BigDecimal lng) {
        return new BranchRequest("Sunrise", "1 Main", null, "Quan 1", "HCM", lat, lng, "0901234567", open, close,
                null, "Vietcombank", bin, "0123456789", "NGUYEN VAN A", null);
    }

    private Set<String> messages(BranchRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void acceptsValidBranch() {
        assertThat(validator.validate(request(LocalTime.of(6, 0), LocalTime.of(22, 0), "970436",
                new BigDecimal("10.76"), new BigDecimal("106.66")))).isEmpty();
    }

    @Test
    void rejectsCloseTimeNotAfterOpenTime() {
        assertThat(messages(request(LocalTime.of(22, 0), LocalTime.of(6, 0), "970436", null, null)))
                .contains("closeTime must be after openTime");
        assertThat(messages(request(LocalTime.of(8, 0), LocalTime.of(8, 0), "970436", null, null)))
                .contains("closeTime must be after openTime");
    }

    @Test
    void rejectsInvalidBankBinAndHalfCoordinates() {
        assertThat(messages(request(LocalTime.of(6, 0), LocalTime.of(22, 0), "97A", new BigDecimal("10"), null)))
                .contains("must be 6 digits", "latitude and longitude must be supplied together");
    }
}
