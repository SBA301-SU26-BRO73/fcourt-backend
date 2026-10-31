package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.staff.integration.SlotSafetyGateway;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

class SlotSafetyGatewayTest {
    final SlotSafetyFixture fixture = new SlotSafetyFixture();
    final SlotSafetyGateway gateway = new SlotSafetyGateway(fixture.target(), fixture.target(), true, 500);

    @AfterEach void close() { gateway.close(); fixture.close(); }

    @Test
    void bothGrpcAuthoritiesMustCheckEverySelectedSlot() {
        assertThatCode(this::check).doesNotThrowAnyException();
        assertThat(fixture.holdCalls.get()).isEqualTo(1);
        assertThat(fixture.bookingCalls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = SlotSafetyFixture.Mode.class, names = {"HOLD_BUSY", "BOOKING_BUSY"})
    void occupiedSlotConflicts(SlotSafetyFixture.Mode mode) {
        fixture.mode = mode;
        assertThatThrownBy(this::check).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    @ParameterizedTest
    @EnumSource(value = SlotSafetyFixture.Mode.class, names = {"MALFORMED", "UNAVAILABLE", "TIMEOUT"})
    void malformedUnavailableAndActualDeadlineFailClosed(SlotSafetyFixture.Mode mode) {
        fixture.mode = mode;
        assertThatThrownBy(this::check).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    void check() { gateway.check(7, 21, LocalDate.of(2030, 1, 1), List.of(37L, 38L), 100, "command", "test-token"); }
}
