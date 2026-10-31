package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fcourt.staff.integration.AvailabilityGateway;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AvailabilityGatewayTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"slotId\":37,\"startTime\":\"18:00\",\"endTime\":\"17:30\",\"price\":1,\"status\":\"AVAILABLE\"}]",
            "[{\"slotId\":37,\"startTime\":\"18:00\",\"endTime\":\"18:30\",\"price\":-1,\"status\":\"AVAILABLE\"}]",
            "[{\"slotId\":37,\"startTime\":\"18:00\",\"endTime\":\"18:30\",\"price\":1,\"status\":\"UNKNOWN\"}]",
            "[{\"slotId\":37,\"startTime\":\"18:00:01\",\"endTime\":\"18:30\",\"price\":1,\"status\":\"AVAILABLE\"}]"
    })
    void malformedSourceFailsClosed(String response) {
        var gateway = fixture(response);
        assertThatThrownBy(() -> gateway.selected(21, LocalDate.of(2030, 1, 1), List.of(37L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void acceptsCanonicalMidnightEndingSlotWithoutChangingPrice() {
        var gateway = fixture("[{\"slotId\":37,\"startTime\":\"23:30\",\"endTime\":\"00:00\",\"price\":1.25,\"status\":\"AVAILABLE\"}]");
        assertThat(gateway.selected(21, LocalDate.of(2030, 1, 1), List.of(37L)).getFirst().price()).isEqualByComparingTo("1.25");
    }

    AvailabilityGateway fixture(String response) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://discovery/api/public/courts/21/slots?date=2030-01-01"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        return new AvailabilityGateway(builder, "http://discovery");
    }
}
