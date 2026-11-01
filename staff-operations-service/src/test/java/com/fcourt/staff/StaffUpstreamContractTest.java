package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.staff.api.StaffRequests;
import com.fcourt.staff.integration.StaffUpstreams;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Verify upstream projections with strict Jackson configuration and actual HTTP. */
class StaffUpstreamContractTest {
    final ObjectMapper json = Jackson2ObjectMapperBuilder.json()
            .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    HttpServer server;
    StaffUpstreams upstream;
    volatile String response;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var builder = RestClient.builder().messageConverters(converters -> {
            converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
            converters.add(new MappingJackson2HttpMessageConverter(json));
        });
        upstream = new StaffUpstreams(builder, url, url);
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void acceptsFullFacilityBranchResponseWithoutChangingRequestStrictness() throws Exception {
        // Facility PR #20 BranchResponse: this projection needs only id/adminId/status.
        response = """
                {"id":7,"adminId":1,"name":"Venue","address":"Test address","ward":"Ward",
                 "district":null,"city":"City","latitude":null,"longitude":null,"phone":null,
                 "openTime":"06:00","closeTime":"22:00","slotDurationMinutes":30,
                 "bankName":"Fixture bank","bankBin":"000000","bankAccountNumber":"000001",
                 "bankAccountName":"Fixture owner","status":"ACTIVE","courtCount":4,
                 "createdAt":"2030-01-01T00:00:00Z","updatedAt":"2030-01-01T00:00:00Z"}
                """;
        assertThatCode(() -> upstream.ownedBranch(7, 1, "fixture-token")).doesNotThrowAnyException();
        assertThat(json.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThatThrownBy(() -> json.readValue("""
                {"fullName":"Staff","phone":"0901234567","shifts":[],"branchId":8}
                """, StaffRequests.Update.class)).isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test void acceptsMinimalAndExtendedStaffBranchViews() {
        response = "{\"id\":7,\"status\":\"ACTIVE\"}";
        assertThatCode(() -> upstream.activeBranch(7, "fixture-token")).doesNotThrowAnyException();
        response = "{\"id\":7,\"status\":\"ACTIVE\",\"name\":\"Branch\"}";
        assertThatCode(() -> upstream.activeBranch(7, "fixture-token")).doesNotThrowAnyException();
    }

    @Test void acceptsExtendedIdentityAccountResponses() {
        response = """
                {"id":100,"email":"staff@fixture.invalid","fullName":"Staff","phone":"0901234567",
                 "role":"STAFF","status":"ACTIVE","createdAt":"2030-01-01T00:00:00Z","avatarUrl":null}
                """;
        upstream.activeUser(100, "STAFF", "fixture-token");
        assertThat(upstream.create(new StaffUpstreams.CreateAccount(7, "staff@fixture.invalid",
                "fixture-password", "Staff", "0901234567"), "fixture-command", "fixture-token").id()).isEqualTo(100);
        assertThat(upstream.update(100, new StaffUpstreams.UpdateAccount(7, "Staff", "0901234567"),
                "fixture-command", "fixture-token").id()).isEqualTo(100);
        response = response.replace("ACTIVE", "INACTIVE");
        assertThatCode(() -> upstream.disable(100, "fixture-command", "fixture-token")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":7,\"adminId\":2,\"status\":\"ACTIVE\",\"name\":\"Other owner\"}",
            "{\"id\":7,\"adminId\":1,\"status\":\"INACTIVE\",\"name\":\"Inactive\"}",
            "{\"id\":7,\"status\":\"ACTIVE\",\"name\":\"Missing owner\"}",
            "{\"id\":8,\"adminId\":1,\"status\":\"ACTIVE\",\"name\":\"Wrong branch\"}"
    })
    void extraFieldsDoNotBypassBranchScopeOrRequiredFields(String payload) {
        response = payload;
        assertThatThrownBy(() -> upstream.ownedBranch(7, 1, "fixture-token")).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":100,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"avatarUrl\":null}",
            "{\"id\":100,\"role\":\"STAFF\",\"status\":\"INACTIVE\",\"avatarUrl\":null}",
            "{\"id\":101,\"role\":\"STAFF\",\"status\":\"ACTIVE\",\"avatarUrl\":null}"
    })
    void extraFieldsDoNotBypassIdentityOrRoleChecks(String payload) {
        response = payload;
        assertThatThrownBy(() -> upstream.activeUser(100, "STAFF", "fixture-token")).isInstanceOf(ResponseStatusException.class);
    }
}
