package com.fcourt.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {"fcourt.payment.events", "fcourt.staff.events", "facility.branch-updated.v1",
        "fcourt.payment.events.DLT"})
class ReportingIntegrationTest {
    /** Singleton container: lives until the JVM exits, after the cached Spring context is closed. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;
    @MockitoBean FacilityClient facility;

    static final long OWNER = 7;

    static String payment(String eventId, String type, long bookingId, long branchId, int amount, LocalDate date, int slots) {
        String slotJson = String.join(",", java.util.Collections.nCopies(slots, "{\"startTime\":\"18:00\"}"));
        return """
                {"eventId":"%s","type":"%s","payment":{"id":%d,"bookingId":%d,"branchId":%d,"amount":%d,
                 "checkout":{"date":"%s","slots":[%s]}}}
                """.formatted(eventId, type, bookingId, bookingId, branchId, amount, date, slotJson);
    }

    @Test
    void foldsEventsIntoOwnerScopedDashboardStats() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var branch1 = new OwnedBranch(1L, OWNER, "ACTIVE", LocalTime.of(6, 0), LocalTime.of(22, 0), 3);
        when(facility.ownedBranch(eq(1L), eq(OWNER), anyString())).thenReturn(branch1);
        when(facility.ownedBranches(eq(OWNER), anyString())).thenReturn(List.of(branch1));
        when(facility.ownedBranch(eq(2L), eq(OWNER), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch access denied"));

        kafka.send("facility.branch-updated.v1", "1", """
                {"branchId":1,"version":2,"name":"A","address":"x","city":"HCM","active":true,"deleted":false,
                 "courts":[{"id":1,"courtTypeId":6,"minPrice":1,"maxPrice":2},{"id":2,"courtTypeId":6,"minPrice":null,"maxPrice":null}]}
                """);
        // Older version arriving late must not overwrite the newer snapshot.
        kafka.send("facility.branch-updated.v1", "1", """
                {"branchId":1,"version":1,"name":"A","address":"x","city":"HCM","active":true,"deleted":false,"courts":[]}
                """);
        kafka.send("fcourt.payment.events", "100", payment("p-1", "PaymentSubmittedEvent", 100, 1, 100000, today, 2));
        kafka.send("fcourt.payment.events", "100", payment("p-2", "PaymentConfirmedEvent", 100, 1, 100000, today, 2));
        kafka.send("fcourt.payment.events", "100", payment("p-2", "PaymentConfirmedEvent", 100, 1, 100000, today, 2));
        kafka.send("fcourt.payment.events", "101", payment("p-3", "PaymentSubmittedEvent", 101, 1, 50000, today, 1));
        kafka.send("fcourt.payment.events", "102", payment("p-4", "PaymentRejectedEvent", 102, 1, 70000, today, 1));
        kafka.send("fcourt.payment.events", "bad", "{not json");
        kafka.send("fcourt.payment.events", "300", payment("p-5", "PaymentConfirmedEvent", 300, 2, 999000, today, 4));
        kafka.send("fcourt.staff.events", "200", """
                {"eventId":"s-1","type":"WalkInBookingCreatedEvent","version":1,"data":{"actorId":3,
                 "occurredAt":"%s","booking":{"bookingId":200,"branchId":1,"courtId":1,"date":"%s",
                 "status":"CHECKED_IN","paymentMethod":"CASH","amount":60000,"invoice":{"items":[{"slotId":1}]}}}}
                """.formatted(Instant.now(), today));
        kafka.send("fcourt.staff.events", "100", """
                {"eventId":"s-2","type":"BookingCheckedInEvent","version":1,"data":{"booking":{"bookingId":100,"branchId":1}}}
                """);
        kafka.flush();

        JsonNode stats = awaitStats(4);
        assertThat(stats.get("branchId").isNull()).isTrue();
        assertThat(stats.get("totalRevenue").decimalValue()).isEqualByComparingTo("160000");
        assertThat(stats.get("todayRevenue").decimalValue()).isEqualByComparingTo("160000");
        assertThat(stats.at("/bookingStatusCounts/CONFIRMED").asLong()).isEqualTo(2);
        assertThat(stats.at("/bookingStatusCounts/PENDING").asLong()).isEqualTo(1);
        assertThat(stats.at("/bookingStatusCounts/CANCELLED").asLong()).isEqualTo(1);
        assertThat(stats.get("activeCourts").asInt()).isEqualTo(2);
        assertThat(stats.get("totalCourts").asInt()).isEqualTo(3);
        // 3 confirmed slots today / (2 active courts * 32 half-hours between 06:00 and 22:00).
        assertThat(stats.get("occupancyRate").asDouble()).isEqualTo(0.0469);

        mvc.perform(get("/api/admin/dashboard/stats").param("branchId", "1").with(owner(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.branchId").value(1))
                .andExpect(jsonPath("$.totalBookings").value(4));
        mvc.perform(get("/api/admin/dashboard/stats").param("branchId", "2").with(owner(OWNER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard/stats").param("branchId", "0").with(owner(OWNER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/dashboard/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard/stats").with(jwt().jwt(t -> t.subject("5"))
                .authorities(new SimpleGrantedAuthority("ROLE_STAFF")))).andExpect(status().isForbidden());

        assertThat(dltValue("fcourt.payment.events.DLT")).isEqualTo("{not json");
    }

    static org.springframework.test.web.servlet.request.RequestPostProcessor owner(long id) {
        return jwt().jwt(t -> t.subject(Long.toString(id)).claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private JsonNode awaitStats(long expectedBookings) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/admin/dashboard/stats").with(owner(OWNER)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            last = json.readTree(body);
            if (last.get("totalBookings").asLong() == expectedBookings && last.get("activeCourts").asInt() == 2
                    && last.get("todayRevenue").decimalValue().signum() > 0
                    && last.at("/bookingStatusCounts/CONFIRMED").asLong() == 2) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Read model not updated in time: " + last);
    }

    private String dltValue(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("reporting-dlt-check", "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) return record.value();
            }
        }
        throw new AssertionError("No record in " + topic);
    }
}
