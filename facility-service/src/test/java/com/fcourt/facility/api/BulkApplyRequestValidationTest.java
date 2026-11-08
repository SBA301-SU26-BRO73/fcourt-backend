package com.fcourt.facility.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.facility.api.TemplateDtos.ApplyTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.BulkApplyRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BulkApplyRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ApplyTemplateRequest template = new ApplyTemplateRequest(null, null, null, null, null, null);

    @Test
    void acceptsDistinctCourtIdsWithTemplate() {
        assertThat(validator.validate(new BulkApplyRequest(List.of(1L, 2L), template))).isEmpty();
    }

    @Test
    void rejectsEmptyDuplicateNonPositiveAndTooManyCourtIds() {
        assertThat(validator.validate(new BulkApplyRequest(List.of(), template))).isNotEmpty();
        assertThat(validator.validate(new BulkApplyRequest(null, template))).isNotEmpty();
        assertThat(validator.validate(new BulkApplyRequest(List.of(1L, 1L), template))).isNotEmpty();
        assertThat(validator.validate(new BulkApplyRequest(List.of(0L), template))).isNotEmpty();
        List<Long> tooMany = LongStream.rangeClosed(1, BulkApplyRequest.MAX_BULK_COURTS + 1).boxed().toList();
        assertThat(validator.validate(new BulkApplyRequest(tooMany, template))).isNotEmpty();
    }

    @Test
    void requiresTemplateAndValidatesItsFields() {
        assertThat(validator.validate(new BulkApplyRequest(List.of(1L), null))).isNotEmpty();
        var free = new ApplyTemplateRequest(null, null, null, BigDecimal.ZERO, null, null);
        assertThat(validator.validate(new BulkApplyRequest(List.of(1L), free))).isNotEmpty();
    }
}
