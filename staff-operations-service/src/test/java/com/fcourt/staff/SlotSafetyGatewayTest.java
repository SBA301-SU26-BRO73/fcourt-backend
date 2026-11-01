package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.staff.integration.SlotSafetyGateway;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

class SlotSafetyGatewayTest {
    final SlotSafetyFixture fixture = new SlotSafetyFixture();
    // First HTTP/2 connection can exceed 500 ms on an instrumented, busy runner.
    // Keep a bounded allowance for fixture startup; test a hanging RPC separately.
    final SlotSafetyGateway gateway = new SlotSafetyGateway(fixture.target(), fixture.target(), true, 5000);

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
    @EnumSource(value = SlotSafetyFixture.Mode.class, names = {"MALFORMED", "UNAVAILABLE"})
    void malformedAndUnavailableFailClosed(SlotSafetyFixture.Mode mode) {
        fixture.mode = mode;
        assertThatThrownBy(this::check).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    @Timeout(10)
    void actualDeadlineFailsClosedAfterAuthorityReceivesTheCall() {
        fixture.mode = SlotSafetyFixture.Mode.TIMEOUT;
        assertThatThrownBy(this::check).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        assertThat(fixture.holdCalls.get()).isEqualTo(1);
        assertThat(fixture.bookingCalls.get()).isZero();
    }

    void check() { gateway.check(7, 21, LocalDate.of(2030, 1, 1), List.of(37L, 38L), 100, "command", "test-token"); }
}
