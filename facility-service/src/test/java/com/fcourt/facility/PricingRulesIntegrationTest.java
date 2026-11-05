package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

/** SCRUM-80/82 pricing rules against PostgreSQL 16 (Testcontainers, needs Docker); Kafka relay disabled. */
@SpringBootTest(properties = {
        "facility.subscription.default-max-branches=3",
        "facility.subscription.default-max-courts=4",
        "facility.events.enabled=false"})
@AutoConfigureMockMvc
class PricingRulesIntegrationTest {
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
    @Autowired JdbcTemplate jdbc;

    static JwtRequestPostProcessor as(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    ResultActions postJson(String url, String body, long owner) throws Exception {
        return mvc.perform(post(url).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    static String rule(String name, String from, String to, int pricePerHour, boolean peak, String days) {
        return """
                {"ruleName":"%s","startTime":"%s","endTime":"%s","pricePerHour":%d,"peakHour":%s%s}
                """.formatted(name, from, to, pricePerHour, peak, days == null ? "" : ",\"daysOfWeek\":" + days);
    }

    @Test
    void weekdayPeakWeekendCopyAndQuoteAreConsistentAcrossScheduleQuoteAndSnapshot() throws Exception {
        long owner = 9301;
        long branchId = id(postJson("/api/admin/branches", FacilityApiIntegrationTest.branchBody("Pricing", "06:00", "22:00"),
                owner).andExpect(status().isCreated()));
        long courtId = id(postJson("/api/admin/branches/" + branchId + "/courts",
                FacilityApiIntegrationTest.courtBody("P1", "100000"), owner).andExpect(status().isCreated()));
        postJson("/api/admin/courts/" + courtId + "/apply-template", "{}", owner).andExpect(status().isOk());
        String rules = "/api/admin/courts/" + courtId + "/pricing-rules";

        // Validation and overlap rules.
        postJson(rules, rule("Bad", "21:00", "17:00", 150000, true, null), owner).andExpect(status().isBadRequest());
        postJson(rules, rule("Grid", "17:10", "21:00", 150000, true, null), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("30-minute")));
        postJson(rules, rule("Free", "17:00", "21:00", 0, true, null), owner).andExpect(status().isBadRequest());
        postJson(rules, rule("Peak", "17:00", "21:00", 150000, true, "[1,2,3,4,5]"), owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dayType").value("WEEKDAY"))
                .andExpect(jsonPath("$.startTime").value("17:00"));
        postJson(rules, rule("Clash", "20:00", "22:00", 90000, false, "[5]"), owner).andExpect(status().isConflict());
        long dayRule = id(postJson(rules, rule("Day", "06:00", "17:00", 100000, false, "[1,2,3,4,5]"), owner)
                .andExpect(status().isCreated()));
        postJson(rules, rule("Peak", "17:00", "21:00", 150000, true, null), 9999).andExpect(status().isNotFound());

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate saturday = monday.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        // 16:30 off-peak half hour 50k, 17:00 peak 75k, 21:00 outside every rule keeps the template price 50k.
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", courtId).param("date", monday.toString()))
                .andExpect(jsonPath("$.slots[21].startTime").value("16:30:00"))
                .andExpect(jsonPath("$.slots[21].price").value(50000.0))
                .andExpect(jsonPath("$.slots[22].price").value(75000.0))
                .andExpect(jsonPath("$.slots[30].startTime").value("21:00:00"))
                .andExpect(jsonPath("$.slots[30].price").value(50000.0));
        // A booking across the boundary is priced slot by slot: 16:30-17:30 = 50k + 75k.
        mvc.perform(get("/api/internal/courts/{id}/price-quote", courtId).param("date", monday.toString())
                        .param("startTime", "16:30").param("endTime", "17:30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(125000.0))
                .andExpect(jsonPath("$.slots[0].source").value("OFF_PEAK"))
                .andExpect(jsonPath("$.slots[1].source").value("PEAK"));
        mvc.perform(get("/api/internal/courts/{id}/price-quote", courtId).param("date", monday.toString())
                        .param("startTime", "16:10").param("endTime", "17:30"))
                .andExpect(status().isBadRequest());
        // Weekend has no rule yet: the template price applies.
        mvc.perform(get("/api/internal/courts/{id}/price-quote", courtId).param("date", saturday.toString())
                        .param("startTime", "17:00").param("endTime", "18:00"))
                .andExpect(jsonPath("$.total").value(100000.0)).andExpect(jsonPath("$.slots[0].source").value("DEFAULT"));

        // Copy weekday rules to the weekend with +20%: Saturday 17:00 = 150000 * 1.2 / 2 = 90000.
        postJson(rules + "/copy-weekday-to-weekend", "{\"priceMultiplier\":1.2}", owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].dayType").value("WEEKEND"));
        mvc.perform(get("/api/internal/courts/{id}/price-quote", courtId).param("date", saturday.toString())
                        .param("startTime", "17:00").param("endTime", "18:00"))
                .andExpect(jsonPath("$.total").value(180000.0)).andExpect(jsonPath("$.slots[0].source").value("WEEKEND"));
        mvc.perform(get("/api/internal/courts/{id}/slot-schedule", courtId).param("date", saturday.toString()))
                .andExpect(jsonPath("$.slots[22].price").value(90000.0));
        // Copying again replaces the weekend rules instead of duplicating them.
        postJson(rules + "/copy-weekday-to-weekend", "{}", owner).andExpect(status().isOk());
        mvc.perform(get(rules).with(as(owner, "ADMIN"))).andExpect(jsonPath("$", hasSize(4)));
        // Weekday prices are untouched by the weekend copy.
        mvc.perform(get("/api/internal/courts/{id}/price-quote", courtId).param("date", monday.toString())
                        .param("startTime", "17:00").param("endTime", "18:00"))
                .andExpect(jsonPath("$.total").value(150000.0));

        // Update, then delete (soft) a rule.
        mvc.perform(put(rules + "/{ruleId}", dayRule).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(rule("Day", "06:00", "17:00", 120000, false, "[1,2,3,4,5]")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pricePerHour").value(120000.0));
        mvc.perform(delete(rules + "/{ruleId}", dayRule).with(as(9999, "ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(delete(rules + "/{ruleId}", dayRule).with(as(owner, "ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(delete(rules + "/{ruleId}", dayRule).with(as(owner, "ADMIN"))).andExpect(status().isNotFound());

        // The branch snapshot for discovery carries the effective price range (the last weekend copy used multiplier 1, so the max is 75000).
        String payload = jdbc.queryForObject("SELECT payload::text FROM facility_outbox WHERE event_key = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, Long.toString(branchId));
        JsonNode court = json.readTree(payload).get("courts").get(0);
        assertThat(court.get("minPrice").decimalValue()).isEqualByComparingTo("50000");
        assertThat(court.get("maxPrice").decimalValue()).isEqualByComparingTo("75000");
    }
}
