package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
        "facility.subscription.default-max-branches=3",
        "facility.subscription.default-max-courts=4",
        "facility.events.relay-delay-ms=200",
        "facility.events.topic-partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = "facility.branch-updated.v1")
class FacilityApiIntegrationTest {
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
    @Autowired ObjectMapper json;
    @Autowired EmbeddedKafkaBroker broker;

    static JwtRequestPostProcessor as(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static String branchBody(String name, String open, String close) {
        return """
                {"name":"%s","address":"123 Nguyen Trai","ward":"Ben Thanh","district":"Quan 1","city":"Ho Chi Minh",
                 "latitude":10.762622,"longitude":106.660172,"phone":"0901234567","openTime":"%s","closeTime":"%s",
                 "bankName":"Vietcombank","bankBin":"970436","bankAccountNumber":"0123456789","bankAccountName":"NGUYEN VAN A"}
                """.formatted(name, open, close);
    }

    static String courtBody(String name, String price) {
        return """
                {"name":"%s","courtTypeId":6,"description":"Indoor","imageUrl":"https://example.com/c.jpg","defaultPricePerHour":%s}
                """.formatted(name, price);
    }

    ResultActions postJson(String url, String body, long owner) throws Exception {
        return mvc.perform(post(url).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void branchCrudIsOwnerScopedValidatedAndLimitedBySubscription() throws Exception {
        long owner = 9101;
        long branchId = id(postJson("/api/admin/branches", branchBody("Sunrise", "06:00", "22:00"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adminId").value(owner))
                .andExpect(jsonPath("$.openTime").value("06:00"))
                .andExpect(jsonPath("$.district").value("Quan 1"))
                .andExpect(jsonPath("$.bankBin").value("970436"))
                .andExpect(jsonPath("$.slotDurationMinutes").value(30))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.courtCount").value(0)));

        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        postJson("/api/admin/branches", branchBody("Bad hours", "22:00", "06:00"), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("closeTime must be after openTime"))));
        mvc.perform(put("/api/admin/branches/{id}", branchId).with(as(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(branchBody("Sunrise 2", "05:00", "23:00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Sunrise 2"))
                .andExpect(jsonPath("$.closeTime").value("23:00"));

        postJson("/api/admin/branches", branchBody("Second", "06:00", "22:00"), owner).andExpect(status().isCreated());
        long third = id(postJson("/api/admin/branches", branchBody("Third", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        postJson("/api/admin/branches", branchBody("Fourth", "06:00", "22:00"), owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("Branch limit")));

        mvc.perform(delete("/api/admin/branches/{id}", third).with(as(owner, "ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/branches").with(as(owner, "ADMIN"))).andExpect(jsonPath("$", hasSize(2)));
        postJson("/api/admin/branches", branchBody("Fourth", "06:00", "22:00"), owner).andExpect(status().isCreated());

        mvc.perform(get("/api/public/branches/{id}", branchId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bankAccountNumber").value("0123456789"))
                .andExpect(jsonPath("$.adminId").doesNotExist());
        mvc.perform(get("/api/staff/branches/{id}", branchId).with(as(55, "STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/staff/branches/{id}", branchId).with(as(owner, "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/branches")).andExpect(status().isUnauthorized());
    }

    @Test
    void courtsAndTemplatesFollowValidationRulesAndPublishSnapshots() throws Exception {
        long owner = 9202;
        long branchId = id(postJson("/api/admin/branches", branchBody("Events", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        String courts = "/api/admin/branches/" + branchId + "/courts";

        long courtId = id(postJson(courts, courtBody("Court A", "100000"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultPricePerHour").value(100000))
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        postJson(courts, courtBody(" court a ", "120000"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Court name already exists in this branch"));
        postJson(courts, courtBody("Court Z", "0"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("defaultPricePerHour"))));
        postJson(courts, courtBody("Court Z", "-5"), owner).andExpect(status().isBadRequest());
        postJson(courts, courtBody("Court X", "100000"), 9999).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/courts/{id}", courtId).with(as(9999, "ADMIN"))).andExpect(status().isNotFound());

        // Subscription court limit = 4 in this test.
        for (String name : List.of("Court B", "Court C", "Court D")) {
            postJson(courts, courtBody(name, "80000"), owner).andExpect(status().isCreated());
        }
        postJson(courts, courtBody("Court E", "80000"), owner).andExpect(status().isForbidden());

        // 30-minute template engine: default branch hours 06:00-22:00 for all 7 days.
        String apply = "/api/admin/courts/" + courtId + "/apply-template";
        postJson("/api/admin/courts/" + courtId + "/time-slot-templates/preview", "{\"priceMultiplier\":1.5}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(32))
                .andExpect(jsonPath("$.slots[0].startTime").value("06:00"))
                .andExpect(jsonPath("$.slots[0].endTime").value("06:30"))
                .andExpect(jsonPath("$.slots[1].startTime").value("06:30"))
                .andExpect(jsonPath("$.slots[1].endTime").value("07:00"))
                .andExpect(jsonPath("$.slots[0].price").value(75000.0));
        postJson(apply, "{}", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(224))
                .andExpect(jsonPath("$.daysOfWeek", hasSize(7)))
                .andExpect(jsonPath("$.slots[0].startTime").value("06:00"))
                .andExpect(jsonPath("$.slots[0].price").value(50000.0));
        // Re-applying a day replaces its layout completely (no fragments of the old one remain).
        postJson(apply, "{\"startTime\":\"08:00\",\"endTime\":\"10:00\",\"price\":90000,\"daysOfWeek\":[1]}", owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSlots").value(4));
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", courtId).param("dayOfWeek", "1")
                        .with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].startTime").value("08:00"))
                .andExpect(jsonPath("$[3].endTime").value("10:00"));
        postJson(apply, "{\"startTime\":\"08:00\",\"endTime\":\"09:15\"}", owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not divisible")));
        postJson(apply, "{\"startTime\":\"05:00\",\"endTime\":\"07:00\"}", owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("branch hours")));
        postJson(apply, "{\"startTime\":\"10:00\",\"endTime\":\"09:00\"}", owner).andExpect(status().isBadRequest());
        postJson(apply, "{\"durationMinutes\":60}", owner).andExpect(status().isBadRequest());

        String single = "/api/admin/courts/" + courtId + "/time-slot-templates";
        postJson(single, "{\"dayOfWeek\":1,\"startTime\":\"09:00\",\"endTime\":\"09:30\",\"price\":1000}", owner)
                .andExpect(status().isConflict());
        long templateId = id(postJson(single,
                "{\"dayOfWeek\":1,\"startTime\":\"10:00\",\"endTime\":\"10:30\",\"price\":1000}", owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true)));
        mvc.perform(delete("/api/admin/time-slot-templates/{id}", templateId).with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/time-slot-templates/{id}", templateId).with(as(owner, "ADMIN")))
                .andExpect(status().isNoContent());

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", courtId).param("date", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outOfService").value(false))
                .andExpect(jsonPath("$.slots", hasSize(4)))
                .andExpect(jsonPath("$.slots[0].startTime").value("08:00:00"))
                .andExpect(jsonPath("$.slots[0].price").value(90000.0));
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", 987654).param("date", monday.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", courtId).param("date", "not-a-date"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/admin/courts/{id}/status", courtId).with(as(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mvc.perform(get("/api/staff/courts/{id}", courtId).with(as(77, "STAFF")))
                .andExpect(jsonPath("$.branchId").value(branchId)).andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", courtId).param("date", monday.toString()))
                .andExpect(jsonPath("$.outOfService").value(true))
                .andExpect(jsonPath("$.slots[0].outOfService").value(true));
        mvc.perform(patch("/api/admin/courts/{id}/status", courtId).with(as(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        // 11 successful changes: branch, 4 courts, 2 applies, 1 create + 1 delete template, 2 status changes.
        JsonNode latest = awaitLatestSnapshot(branchId, 11);
        assertThat(latest.get("active").asBoolean()).isTrue();
        assertThat(latest.get("deleted").asBoolean()).isFalse();
        assertThat(latest.get("district").asText()).isEqualTo("Quan 1");
        assertThat(latest.get("courts")).hasSize(4);
        JsonNode first = latest.get("courts").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(courtId);
        assertThat(first.get("minPrice").decimalValue()).isEqualByComparingTo("50000");
        assertThat(first.get("maxPrice").decimalValue()).isEqualByComparingTo("90000");
        assertThat(latest.get("courts").get(1).get("minPrice").isNull()).isTrue();
        assertThat(latest.has("bankAccountNumber")).isFalse();
    }

    /** Reads the topic until the snapshot with the expected version of the branch arrives. */
    private JsonNode awaitLatestSnapshot(long branchId, long expectedVersion) throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps("facility-test-" + branchId, "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("facility.branch-updated.v1"));
            long lastVersion = 0;
            JsonNode last = null;
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (!record.key().equals(Long.toString(branchId))) continue;
                    JsonNode event = json.readTree(record.value());
                    long version = event.get("version").asLong();
                    assertThat(version).as("versions strictly increase per branch").isGreaterThan(lastVersion);
                    lastVersion = version;
                    last = event;
                }
                if (lastVersion == expectedVersion) return last;
            }
            throw new AssertionError("Snapshot version " + expectedVersion + " not received, last=" + lastVersion);
        }
    }
}
