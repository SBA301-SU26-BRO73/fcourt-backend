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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fcourt.facility.integration.SlotAvailabilityClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.Mockito;
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

    @MockitoBean SlotAvailabilityClient availability;
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
        mvc.perform(get("/api/staff/branches/{id}/courts", branchId).with(as(55, "STAFF")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/staff/branches/{id}/courts", branchId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/branches/{id}/courts", branchId).with(as(owner, "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/branches")).andExpect(status().isUnauthorized());
    }

    @Test
    void bookingContextContainsOnlyAvailableVenueMetadata() throws Exception {
        long owner=9918;
        long branch=id(postJson("/api/admin/branches",branchBody("Booking fixture","06:00","22:00"),owner).andExpect(status().isCreated()));
        long court=id(postJson("/api/admin/branches/"+branch+"/courts",courtBody("Court context","100000"),owner).andExpect(status().isCreated()));
        mvc.perform(get("/api/internal/courts/{id}/booking-context",court)).andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(branch)).andExpect(jsonPath("$.courtName").value("Court context"))
                .andExpect(jsonPath("$.bankAccountNumber").doesNotExist()).andExpect(jsonPath("$.adminId").doesNotExist());
        mvc.perform(patch("/api/admin/courts/{id}/status",court).with(as(owner,"ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/internal/courts/{id}/booking-context",court)).andExpect(status().isConflict());
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

    @Test
    void staffCourtProjectionListsLiveCourtsOfActiveBranchOnly() throws Exception {
        long owner = 9305;
        long branchId = id(postJson("/api/admin/branches", branchBody("Board Hall", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        String branchUrl = "/api/admin/branches/" + branchId;
        long courtA = id(postJson(branchUrl + "/courts", courtBody("Court A", "100000"), owner).andExpect(status().isCreated()));
        long courtB = id(postJson(branchUrl + "/courts", courtBody("Court B", "100000"), owner).andExpect(status().isCreated()));
        long courtC = id(postJson(branchUrl + "/courts", courtBody("Court C", "100000"), owner).andExpect(status().isCreated()));
        mvc.perform(patch("/api/admin/courts/{id}/status", courtB).with(as(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/admin/courts/{id}", courtC).with(as(owner, "ADMIN"))).andExpect(status().isNoContent());

        String staffUrl = "/api/staff/branches/" + branchId + "/courts";
        mvc.perform(get(staffUrl).with(as(55, "STAFF")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(courtA))
                .andExpect(jsonPath("$[0].branchId").value(branchId))
                .andExpect(jsonPath("$[0].name").value("Court A"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].defaultPricePerHour").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(courtB))
                .andExpect(jsonPath("$[1].status").value("MAINTENANCE"));
        mvc.perform(get("/api/staff/branches/{id}/courts", branchId + 100_000).with(as(55, "STAFF")))
                .andExpect(status().isNotFound());

        mvc.perform(put(branchUrl).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(branchBody("Board Hall", "06:00", "22:00")
                                .replace("\"bankBin\"", "\"status\":\"INACTIVE\",\"bankBin\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get(staffUrl).with(as(55, "STAFF"))).andExpect(status().isConflict());

        mvc.perform(delete(branchUrl).with(as(owner, "ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get(staffUrl).with(as(55, "STAFF"))).andExpect(status().isNotFound());
    }

    @Test
    void branchNamesAreUniqueAndSlotDurationIsKeptUntilTemplatesExist() throws Exception {
        long owner = 9303;
        long other = 9304;
        String hourly = branchBody("Unique Hall", "06:00", "22:00").replace("{\"name\"", "{\"slotDurationMinutes\":60,\"name\"");
        long branchId = id(postJson("/api/admin/branches", hourly, owner).andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotDurationMinutes").value(60)));

        postJson("/api/admin/branches", branchBody("Unique Hall", "06:00", "22:00"), other)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Branch name already exists"));
        postJson("/api/admin/branches", branchBody("  Unique Hall ", "06:00", "22:00"), other)
                .andExpect(status().isBadRequest());
        postJson("/api/admin/branches", branchBody("Other Hall", "06:00", "22:00"), other).andExpect(status().isCreated());

        String branchUrl = "/api/admin/branches/" + branchId;
        mvc.perform(put(branchUrl).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(branchBody("Unique Hall", "05:00", "23:00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slotDurationMinutes").value(60));
        mvc.perform(put(branchUrl).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(branchBody("Other Hall", "05:00", "23:00")))
                .andExpect(status().isBadRequest());

        long courtId = id(postJson(branchUrl + "/courts", courtBody("Court A", "100000"), owner)
                .andExpect(status().isCreated()));
        String apply = "/api/admin/courts/" + courtId + "/apply-template";
        postJson(apply, "{\"price\":0,\"daysOfWeek\":[1]}", owner).andExpect(status().isBadRequest());
        postJson(apply, "{\"priceMultiplier\":9999,\"daysOfWeek\":[1]}", owner).andExpect(status().isBadRequest());
        postJson(apply, "{\"startTime\":\"08:00\",\"endTime\":\"09:00\",\"daysOfWeek\":[1]}", owner)
                .andExpect(status().isOk());

        mvc.perform(put(branchUrl).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(hourly.replace("\"slotDurationMinutes\":60", "\"slotDurationMinutes\":30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("slot duration")));
        mvc.perform(put(branchUrl).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(hourly))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slotDurationMinutes").value(60));

        mvc.perform(delete(branchUrl).with(as(owner, "ADMIN"))).andExpect(status().isNoContent());
        postJson("/api/admin/branches", branchBody("Unique Hall", "06:00", "22:00"), other).andExpect(status().isCreated());
    }

    @Test
    void superAdminManagesAnyOwnersFacilitiesButCannotOwnBranchesAndOthersStayDenied() throws Exception {
        long owner = 9301;
        long superAdmin = 9302;
        long branchId = id(postJson("/api/admin/branches", branchBody("Super Hall", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));

        mvc.perform(get("/api/admin/branches").with(as(superAdmin, "SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id==" + branchId + ")]").exists());
        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(superAdmin, "SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adminId").value(owner));
        mvc.perform(put("/api/admin/branches/{id}", branchId).with(as(superAdmin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(branchBody("Super Hall 2", "05:00", "23:00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Super Hall 2"));

        long courtId = id(mvc.perform(post("/api/admin/branches/{id}/courts", branchId).with(as(superAdmin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(courtBody("Court S1", "100000")))
                .andExpect(status().isCreated()));
        mvc.perform(get("/api/admin/courts/{id}", courtId).with(as(superAdmin, "SUPER_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", courtId).with(as(superAdmin, "SUPER_ADMIN")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/branches").with(as(superAdmin, "SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(branchBody("Super Owned", "06:00", "22:00")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/branches").with(as(55, "STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(9999, "ADMIN"))).andExpect(status().isNotFound());

        mvc.perform(delete("/api/admin/branches/{id}", branchId).with(as(superAdmin, "SUPER_ADMIN")))
                .andExpect(status().isNoContent());
    }
    @Test
    void bulkApplyAppliesToManyCourtsAtomically() throws Exception {
        long owner = 9306;
        long branchA = id(postJson("/api/admin/branches", branchBody("Bulk A", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        long branchB = id(postJson("/api/admin/branches", branchBody("Bulk B", "08:00", "20:00"), owner)
                .andExpect(status().isCreated()));
        long a1 = id(postJson("/api/admin/branches/" + branchA + "/courts", courtBody("A1", "100000"), owner));
        long a2 = id(postJson("/api/admin/branches/" + branchA + "/courts", courtBody("A2", "80000"), owner));
        long b1 = id(postJson("/api/admin/branches/" + branchB + "/courts", courtBody("B1", "60000"), owner));
        String bulk = "/api/admin/courts/bulk-apply-template";

        // Preview: per-court slots; B1 cannot take 06:00-08:00 (outside its branch hours) and is flagged, not failed.
        String early = "{\"startTime\":\"06:00\",\"endTime\":\"08:00\",\"daysOfWeek\":[1,2]}";
        postJson(bulk + "/preview", "{\"courtIds\":[" + a1 + "," + a2 + "," + b1 + "],\"template\":" + early + "}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflicts").value(true))
                .andExpect(jsonPath("$.courts", hasSize(3)))
                .andExpect(jsonPath("$.courts[0].totalSlots").value(8))
                .andExpect(jsonPath("$.courts[0].slots[0].price").value(50000.0))
                .andExpect(jsonPath("$.courts[1].slots[0].price").value(40000.0))
                .andExpect(jsonPath("$.courts[2].conflict", containsString("branch hours")))
                .andExpect(jsonPath("$.totalSlots").value(16));

        // Atomic: B1 conflicts, so A1/A2 stay untouched (rollback) and the 409 lists the conflicting court.
        postJson(bulk, "{\"courtIds\":[" + a1 + "," + a2 + "," + b1 + "],\"template\":" + early + "}", owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].courtId").value(b1));
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", a1).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(0)));

        // Success across two branches; reading back matches the chosen configuration.
        String morning = "{\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"daysOfWeek\":[1,2]}";
        postJson(bulk, "{\"courtIds\":[" + a1 + "," + a2 + "," + b1 + "],\"template\":" + morning + "}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(24))
                .andExpect(jsonPath("$.courts", hasSize(3)))
                .andExpect(jsonPath("$.courts[0].slotsPerDay").value(4));
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", b1).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].price").value(30000.0));
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", a2).param("dayOfWeek", "2")
                        .with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].price").value(40000.0));

        // Request-level validation and ownership.
        postJson(bulk, "{\"courtIds\":[],\"template\":{}}", owner).andExpect(status().isBadRequest());
        postJson(bulk, "{\"courtIds\":[" + a1 + "," + a1 + "],\"template\":{}}", owner).andExpect(status().isBadRequest());
        postJson(bulk, "{\"courtIds\":[" + a1 + "]}", owner).andExpect(status().isBadRequest());
        postJson(bulk, "{\"courtIds\":[" + a1 + "],\"template\":{\"startTime\":\"08:00\",\"endTime\":\"09:15\"}}", owner)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", containsString("not divisible")));
        postJson(bulk, "{\"courtIds\":[" + a1 + "," + a2 + "],\"template\":{}}", 9999).andExpect(status().isNotFound());
        postJson(bulk, "{\"courtIds\":[" + a1 + ",999999],\"template\":{}}", owner).andExpect(status().isNotFound());
        mvc.perform(post(bulk).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/courts/{id}/time-slot-templates", a1).param("dayOfWeek", "3")
                        .with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(0)));
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

    @Test
    void imageUploadAnswers503WhenCloudinaryIsNotConfiguredButServiceStillWorks() throws Exception {
        long owner = 9390;
        long branchId = id(postJson("/api/admin/branches", branchBody("No cloud", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/admin/branches/{id}/images", branchId)
                        .file(new org.springframework.mock.web.MockMultipartFile("files", "a.png", "image/png", png))
                        .with(as(owner, "ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Image storage is not configured on this server"));
        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(owner, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images", hasSize(0)));
    }

    @Test
    void courtLocksCloseSlotsRefuseBookingConflictsAndUnlock() throws Exception {
        long owner = 9307;
        long branchId = id(postJson("/api/admin/branches", branchBody("Locks", "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        String courts = "/api/admin/branches/" + branchId + "/courts";
        long courtA = id(postJson(courts, courtBody("Court A", "100000"), owner).andExpect(status().isCreated()));
        long courtB = id(postJson(courts, courtBody("Court B", "100000"), owner).andExpect(status().isCreated()));
        for (long court : List.of(courtA, courtB)) {
            postJson("/api/admin/courts/" + court + "/apply-template",
                    "{\"startTime\":\"08:00\",\"endTime\":\"10:00\",\"price\":90000,\"daysOfWeek\":[1]}", owner)
                    .andExpect(status().isOk());
        }
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        String schedule = "/api/internal/courts/{id}/slot-schedule";
        Mockito.when(availability.conflicts(Mockito.anyLong(), Mockito.any())).thenReturn(List.of());

        // SCRUM-49: lock 08:30-09:30 as a private event with public contact data and internal notes.
        String lockBody = """
                {"courtId":%d,"startAt":"%sT08:30:00","endAt":"%sT09:30:00","type":"PRIVATE_EVENT",
                 "reason":"Corporate event","notes":"Deposit paid in cash","publicReason":"Giai dau noi bo",
                 "contactName":"Event desk","contactPhone":"0901234567"}
                """.formatted(courtA, monday, monday);
        long lockId = id(postJson("/api/admin/courts/lock-slots", lockBody, owner).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PRIVATE_EVENT"))
                .andExpect(jsonPath("$.startAt").value(monday + "T08:30:00"))
                .andExpect(jsonPath("$.notes").value("Deposit paid in cash")));
        mvc.perform(get(schedule, courtA).param("date", monday.toString()))
                .andExpect(jsonPath("$.outOfService").value(false))
                .andExpect(jsonPath("$.slots[0].outOfService").value(false))
                .andExpect(jsonPath("$.slots[0].restriction").doesNotExist())
                .andExpect(jsonPath("$.slots[1].outOfService").value(true))
                .andExpect(jsonPath("$.slots[1].restriction.type").value("PRIVATE_EVENT"))
                .andExpect(jsonPath("$.slots[1].restriction.publicReason").value("Giai dau noi bo"))
                .andExpect(jsonPath("$.slots[1].restriction.contactPhone").value("0901234567"))
                .andExpect(jsonPath("$.slots[1].restriction.reason").doesNotExist())
                .andExpect(jsonPath("$.slots[1].restriction.notes").doesNotExist())
                .andExpect(jsonPath("$.slots[2].outOfService").value(true))
                .andExpect(jsonPath("$.slots[3].outOfService").value(false));
        mvc.perform(get(schedule, courtB).param("date", monday.toString()))
                .andExpect(jsonPath("$.slots[1].outOfService").value(false));
        mvc.perform(get(schedule, courtA).param("date", monday.plusDays(7).toString()))
                .andExpect(jsonPath("$.slots[1].outOfService").value(false));

        postJson("/api/admin/courts/lock-slots", lockBody.replace("08:30:00", "09:00:00"), owner)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCK_OVERLAP"));
        postJson("/api/admin/courts/lock-slots", lockBody, 9999).andExpect(status().isNotFound());
        postJson("/api/admin/courts/lock-slots", lockBody.replace("08:30:00", "08:10:00"), owner)
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/courts/lock-slots").param("branchId", Long.toString(branchId))
                        .with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(lockId));
        mvc.perform(get("/api/admin/courts/lock-slots").param("branchId", Long.toString(branchId))
                        .with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/admin/courts/lock-slots/{id}", lockId).with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/courts/lock-slots/{id}", lockId).with(as(owner, "ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/courts/lock-slots/{id}", lockId).with(as(owner, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get(schedule, courtA).param("date", monday.toString()))
                .andExpect(jsonPath("$.slots[1].outOfService").value(false));

        // Booking/hold conflict: refused with the colliding slots, nothing stored.
        Mockito.when(availability.conflicts(courtA, monday)).thenReturn(List.of(
                new SlotAvailabilityClient.SlotConflict(1L, java.time.LocalTime.of(9, 0),
                        java.time.LocalTime.of(9, 30), "BOOKED")));
        postJson("/api/admin/courts/lock-slots", lockBody, owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.conflicts[0].startTime").value("09:00"));
        mvc.perform(get("/api/admin/courts/lock-slots").param("branchId", Long.toString(branchId))
                        .with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(0)));
        Mockito.when(availability.conflicts(courtA, monday)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down"));
        postJson("/api/admin/courts/lock-slots", lockBody, owner).andExpect(status().isServiceUnavailable());
        Mockito.reset(availability);
        Mockito.when(availability.conflicts(Mockito.anyLong(), Mockito.any())).thenReturn(List.of());

        // SCRUM-111: one command locks every court of the branch; one command unlocks the group.
        String branchLock = """
                {"branchId":%d,"allCourts":true,"startAt":"%sT08:00:00","endAt":"%sT10:00:00",
                 "reason":"Tournament","publicReason":"Giai dau"}
                """.formatted(branchId, monday, monday);
        String group = json.readTree(postJson("/api/admin/courts/out-of-service", branchLock, owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalLocks").value(2))
                .andExpect(jsonPath("$.locks[0].type").value("OUT_OF_SERVICE"))
                .andReturn().getResponse().getContentAsString()).get("groupId").asText();
        for (long court : List.of(courtA, courtB)) {
            mvc.perform(get(schedule, court).param("date", monday.toString()))
                    .andExpect(jsonPath("$.slots[*].outOfService", org.hamcrest.Matchers.everyItem(
                            org.hamcrest.Matchers.is(true))))
                    .andExpect(jsonPath("$.slots[0].restriction.type").value("OUT_OF_SERVICE"));
        }
        postJson("/api/admin/courts/out-of-service", branchLock, owner)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCK_OVERLAP"));
        postJson("/api/admin/courts/out-of-service",
                branchLock.replace("\"allCourts\":true", "\"allCourts\":false,\"courtIds\":[999999]"), owner)
                .andExpect(status().isNotFound());
        postJson("/api/admin/courts/out-of-service", branchLock.replace("\"allCourts\":true,", ""), owner)
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/courts/out-of-service/{id}", group).with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/courts/out-of-service/{id}", group).with(as(owner, "ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(get(schedule, courtB).param("date", monday.toString()))
                .andExpect(jsonPath("$.slots[0].outOfService").value(false));

        // A window in the past is refused.
        postJson("/api/admin/courts/lock-slots", lockBody.replace(monday.toString(), "2020-01-06"), owner)
                .andExpect(status().isBadRequest());
    }
}
