package com.fcourt.discovery.component;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SlotSourceClientTest {
    private HttpServer server;
    private SlotSourceClient client;
    private int status;
    private String body;
    private String path;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().toString();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new SlotSourceClient(url, url);
        status = 200;
    }

    @AfterEach
    void close() { server.stop(0); }

    @Test
    void readsBothRealHttpContracts() {
        body = """
                {"courtId":1,"date":"2030-01-01","outOfService":false,"slots":[
                {"slotId":1,"startTime":"18:00:00","endTime":"18:30:00","price":75000.25,"outOfService":false}]}
                """;
        var schedule = client.schedule(1, LocalDate.of(2030, 1, 1));
        assertThat(schedule.slots().getFirst().price()).isEqualByComparingTo("75000.25");
        assertThat(path).isEqualTo("/api/internal/courts/1/slot-schedule?date=2030-01-01");
        body = "{\"courtId\":1,\"date\":\"2030-01-01\",\"bookedSlotIds\":[1]}";
        assertThat(client.occupancy(1, LocalDate.of(2030, 1, 1)).bookedSlotIds()).containsExactly(1L);
        assertThat(path).isEqualTo("/api/internal/courts/1/slot-occupancy?date=2030-01-01");
    }

    @Test
    void mapsMissingCourtAndUpstreamFailures() {
        body = "{}";
        status = 404;
        assertStatus(404, () -> client.schedule(1, LocalDate.now()));
        assertStatus(503, () -> client.occupancy(1, LocalDate.now()));
        status = 500;
        assertStatus(503, () -> client.schedule(1, LocalDate.now()));
        status = 200;
        body = "not-json";
        assertStatus(503, () -> client.schedule(1, LocalDate.now()));
    }

    private void assertStatus(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(expected));
    }
}
