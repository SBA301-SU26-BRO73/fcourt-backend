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

    @Test
    void toleratesDiscoveryLockRestrictionUnderStrictJackson() {
        // discovery-service (SCRUM-111) adds an optional "restriction" object to OUT_OF_SERVICE slots; the
        // production RestClient.Builder runs with fail-on-unknown-properties=true, so the projection must ignore it.
        var strict = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).build();
        var builder = RestClient.builder().messageConverters(converters -> {
            converters.removeIf(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class::isInstance);
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(strict));
        });
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://discovery/api/public/courts/21/slots?date=2030-01-01")).andRespond(withSuccess(
                "[{\"slotId\":37,\"startTime\":\"18:00\",\"endTime\":\"18:30\",\"price\":1,\"status\":\"AVAILABLE\"},"
                        + "{\"slotId\":38,\"startTime\":\"18:30\",\"endTime\":\"19:00\",\"price\":1,\"status\":\"OUT_OF_SERVICE\","
                        + "\"restriction\":{\"type\":\"PRIVATE_EVENT\",\"publicReason\":\"Giai dau\"}}]",
                MediaType.APPLICATION_JSON));
        var gateway = new AvailabilityGateway(builder, "http://discovery");
        assertThat(gateway.selected(21, LocalDate.of(2030, 1, 1), List.of(37L))).hasSize(1);
        server.verify();
    }

    AvailabilityGateway fixture(String response) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://discovery/api/public/courts/21/slots?date=2030-01-01"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        return new AvailabilityGateway(builder, "http://discovery");
    }
}
