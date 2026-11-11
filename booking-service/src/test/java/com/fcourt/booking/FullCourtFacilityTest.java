package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

/** HTTP contract of the facility reservation client against a fixture server (no Docker needed). */
class FullCourtFacilityTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    HttpServer server;
    volatile int status = 201;
    volatile String body = "{\"reservationId\":\"" + ID + "\",\"state\":\"RESERVED\",\"courtIds\":[1,2],\"extra\":true}";
    final AtomicReference<String> lastPath = new AtomicReference<>();
    final AtomicReference<String> lastToken = new AtomicReference<>();
    final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            lastToken.set(exchange.getRequestHeaders().getFirst(FullCourtFacility.TOKEN_HEADER));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    FullCourtFacility client(String token) {
        return new FullCourtFacility(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), token);
    }

    FullCourtFacility.Reserve reserve() {
        return new FullCourtFacility.Reserve(ID, 7, 10, List.of(1L, 2L), "2030-01-02T08:00", "2030-01-02T09:00", 120, null);
    }

    @Test void reserveSendsTheServiceTokenAndTheReservationIdAndReadsTheAnswer() {
        var result = client(TOKEN).reserve(reserve());
        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(lastPath.get()).isEqualTo("POST /api/internal/court-reservations");
        assertThat(lastToken.get()).isEqualTo(TOKEN);
        assertThat(lastBody.get()).contains(ID.toString()).contains("\"courtIds\":[1,2]").contains("2030-01-02T08:00");
    }

    @Test void commitAndRollbackUseTheirOwnRoutesAndExpectedStates() {
        body = "{\"reservationId\":\"" + ID + "\",\"state\":\"COMMITTED\"}";
        status = 200;
        assertThat(client(TOKEN).commit(ID).state()).isEqualTo("COMMITTED");
        assertThat(lastPath.get()).isEqualTo("POST /api/internal/court-reservations/" + ID + "/commit");
        body = "{\"reservationId\":\"" + ID + "\",\"state\":\"CANCELLED\"}";
        assertThat(client(TOKEN).rollback(ID).state()).isEqualTo("CANCELLED");
        assertThat(lastPath.get()).isEqualTo("POST /api/internal/court-reservations/" + ID + "/rollback");
    }

    @Test void definiteClientErrorsCarryTheFacilityProblemCode() {
        status = 409;
        body = "{\"status\":409,\"code\":\"LOCK_OVERLAP\"}";
        assertThatThrownBy(() -> client(TOKEN).reserve(reserve())).isInstanceOfSatisfying(FullCourtFacility.Refused.class,
                refused -> { assertThat(refused.status).isEqualTo(409); assertThat(refused.code).isEqualTo("LOCK_OVERLAP"); });
        status = 403;
        body = "not json";
        assertThatThrownBy(() -> client(TOKEN).commit(ID)).isInstanceOfSatisfying(FullCourtFacility.Refused.class,
                refused -> assertThat(refused.status).isEqualTo(403));
    }

    @Test void serverErrorsMalformedAnswersAndMissingCredentialAreUnknownNeverSuccess() {
        status = 503;
        assertThatThrownBy(() -> client(TOKEN).reserve(reserve())).isInstanceOf(FullCourtFacility.Unavailable.class);
        status = 201;
        body = "not-json";
        assertThatThrownBy(() -> client(TOKEN).reserve(reserve())).isInstanceOf(FullCourtFacility.Unavailable.class);
        body = "{\"reservationId\":\"" + UUID.randomUUID() + "\",\"state\":\"RESERVED\"}";
        assertThatThrownBy(() -> client(TOKEN).reserve(reserve())).isInstanceOf(FullCourtFacility.Unavailable.class);
        body = "{\"reservationId\":\"" + ID + "\",\"state\":\"CANCELLED\"}";
        assertThatThrownBy(() -> client(TOKEN).reserve(reserve())).isInstanceOf(FullCourtFacility.Unavailable.class);
        lastPath.set(null);
        assertThatThrownBy(() -> client("").reserve(reserve())).isInstanceOf(FullCourtFacility.Unavailable.class);
        assertThatThrownBy(() -> client("short").commit(ID)).isInstanceOf(FullCourtFacility.Unavailable.class);
        assertThat(lastPath.get()).isNull(); // nothing was sent without a credential
    }
}
