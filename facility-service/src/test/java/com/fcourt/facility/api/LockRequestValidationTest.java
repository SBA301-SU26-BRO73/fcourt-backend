package com.fcourt.facility.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.facility.api.LockDtos.BranchLockRequest;
import com.fcourt.facility.api.LockDtos.LockSlotsRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockRequestValidationTest {
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final LocalDateTime FROM = LocalDateTime.of(2030, 1, 5, 13, 0);
    static final LocalDateTime TO = LocalDateTime.of(2030, 1, 5, 18, 0);

    @Test
    void lockSlotsRequiresCourtWindowAndReason() {
        assertThat(VALIDATOR.validate(new LockSlotsRequest(1L, FROM, TO, null, "Repair", null, null, null, null))).isEmpty();
        assertThat(VALIDATOR.validate(new LockSlotsRequest(null, null, null, null, " ", null, null, null, null)))
                .hasSize(4);
    }

    @Test
    void contactPhoneUsesTheFormatTheFlutterClientCanDial() {
        for (String ok : List.of("0901234567", "+84901234567")) {
            assertThat(VALIDATOR.validate(new LockSlotsRequest(1L, FROM, TO, null, "x", null, null, null, ok))).isEmpty();
        }
        for (String bad : List.of("123", "tel:0901234567", "0901;234567", "+1234567890123456")) {
            assertThat(VALIDATOR.validate(new LockSlotsRequest(1L, FROM, TO, null, "x", null, null, null, bad)))
                    .as(bad).hasSize(1);
        }
    }

    @Test
    void publicFieldsHonourTheContractLengths() {
        assertThat(VALIDATOR.validate(new LockSlotsRequest(1L, FROM, TO, null, "x", null, "a".repeat(501), null, null)))
                .hasSize(1);
        assertThat(VALIDATOR.validate(new LockSlotsRequest(1L, FROM, TO, null, "x", null, "a".repeat(500), "n".repeat(101), null)))
                .hasSize(1);
    }

    @Test
    void branchLockNeedsExactlyOneScope() {
        assertThat(VALIDATOR.validate(new BranchLockRequest(1L, true, null, FROM, TO, null, "x", null, null, null, null))).isEmpty();
        assertThat(VALIDATOR.validate(new BranchLockRequest(1L, false, List.of(2L, 3L), FROM, TO, null, "x", null, null, null, null))).isEmpty();
        assertThat(VALIDATOR.validate(new BranchLockRequest(1L, null, null, FROM, TO, null, "x", null, null, null, null))).hasSize(1);
        assertThat(VALIDATOR.validate(new BranchLockRequest(1L, true, List.of(2L), FROM, TO, null, "x", null, null, null, null))).hasSize(1);
        assertThat(VALIDATOR.validate(new BranchLockRequest(1L, false, List.of(2L, 2L), FROM, TO, null, "x", null, null, null, null))).hasSize(1);
    }
}
