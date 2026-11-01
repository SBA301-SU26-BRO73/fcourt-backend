package com.fcourt.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Catalogue API end to end through the real security chain (RS256 + JWKS) and Postgres/Flyway. Needs Docker. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiIntegrationTest extends IntegrationSupport {
    static final long SUPER_ADMIN = 16;
    static final long CUSTOMER = 6;
    static final long OWNER = 1;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void courtTypesCanBeReadWithoutAToken() throws Exception {
        // Other tests share the database, so only the seeded rows are guaranteed.
        var page = body(mvc.perform(get("/api/court-types")).andExpect(status().isOk()));
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(page.get("content").size()).isGreaterThanOrEqualTo(6);
        mvc.perform(get("/api/court-types/1")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(get("/api/court-types/99999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void plansOnSaleAreMeantForThePricingPageSoTheyArePublicAndCheapestFirst() throws Exception {
        mvc.perform(get("/api/subscription-plans/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].features").isNotEmpty());
        JsonNode plans = body(mvc.perform(get("/api/subscription-plans/all")));
        assertThat(plans.size()).isGreaterThanOrEqualTo(3);
        long previous = Long.MIN_VALUE;
        for (JsonNode plan : plans) {
            long price = plan.get("monthlyPrice").asLong();
            assertThat(price).isGreaterThanOrEqualTo(previous);
            previous = price;
        }
    }

    @Test
    void thePagedPlanListAndPlanByIdNeedASignedInUser() throws Exception {
        mvc.perform(get("/api/subscription-plans")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subscription-plans/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subscription-plans").header("Authorization", bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").isNumber());
        mvc.perform(get("/api/subscription-plans/1").header("Authorization", bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features").isArray());
    }

    @Test
    void writesNeedASuperAdminToken() throws Exception {
        String body = courtTypeJson(unique("Pickleball"));

        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/court-types").header("Authorization", bearer(CUSTOMER, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/court-types").header("Authorization", bearer(OWNER, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/subscription-plans/1").header("Authorization", bearer(OWNER, "ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/court-types").header("Authorization", "Bearer " + token(SUPER_ADMIN, "SUPER_ADMIN", "another-audience"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void routesThatAreNotListedAreDeniedEvenForASuperAdmin() throws Exception {
        mvc.perform(get("/api/unknown").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void courtTypeLifecycleIncludingSoftDeleteAndNameReuse() throws Exception {
        String name = unique("Pickleball");
        long id = createCourtType(courtTypeJson(name)).get("id").asLong();

        mvc.perform(post("/api/court-types").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(courtTypeJson(name)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Court type name already exists"));
        mvc.perform(put("/api/court-types/" + id).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"nameEn\":\"Updated\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameEn").value("Updated"))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/court-types").param("active", "false"))
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));
        mvc.perform(get("/api/court-types").param("active", "true"))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem((int) id))));
        // A PUT that leaves out the flag keeps the type disabled.
        mvc.perform(put("/api/court-types/" + id).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(courtTypeJson(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(delete("/api/court-types/" + id).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/court-types/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM court_types WHERE id = ?",
                Boolean.class, id)).isTrue();
        // The partial unique index frees the name once the type is deleted.
        createCourtType(courtTypeJson(name));
    }

    @Test
    void renamingACourtTypeOntoAnExistingNameConflicts() throws Exception {
        String first = unique("Alpha");
        long second = createCourtType(courtTypeJson(unique("Beta"))).get("id").asLong();
        createCourtType(courtTypeJson(first));

        mvc.perform(put("/api/court-types/" + second).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(courtTypeJson(first)))
                .andExpect(status().isConflict());
    }

    @Test
    void planLifecycleWithFeaturesActiveFlagAndSoftDelete() throws Exception {
        String name = unique("Gold");
        var created = body(mvc.perform(post("/api/subscription-plans")
                        .header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson(name, "[\" Reports \",\"\",\"Reports\",\"Support\"]", "true")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.features.length()").value(2))
                .andExpect(jsonPath("$.features[0]").value("Reports"))
                .andExpect(jsonPath("$.active").value(true)));
        long id = created.get("id").asLong();

        mvc.perform(get("/api/subscription-plans/all"))
                .andExpect(jsonPath("$[*].name").value(hasItem(name)));

        mvc.perform(put("/api/subscription-plans/" + id).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson(name, "[\"Only one\"]", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(1))
                .andExpect(jsonPath("$.active").value(false));
        // An inactive plan is hidden from the pricing page but still readable by id.
        mvc.perform(get("/api/subscription-plans/all"))
                .andExpect(jsonPath("$[*].name").value(not(hasItem(name))));
        mvc.perform(get("/api/subscription-plans/" + id).header("Authorization", bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/subscription-plans/" + id).header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/subscription-plans/" + id).header("Authorization", bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isNotFound());
        // Soft delete keeps the row (and its features) so past subscriptions still resolve.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_plans WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        // The partial unique index frees the name once the plan is deleted.
        mvc.perform(post("/api/subscription-plans").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(planJson(name, "[]", "true")))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicatePlanNameConflictsAndInvalidBodiesListTheirFields() throws Exception {
        String name = unique("Silver");
        mvc.perform(post("/api/subscription-plans").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(planJson(name, "[]", "true")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/subscription-plans").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(planJson(name, "[]", "true")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Subscription plan name already exists"));
        mvc.perform(post("/api/subscription-plans").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"maxBranches\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    private JsonNode createCourtType(String bodyJson) throws Exception {
        return body(mvc.perform(post("/api/court-types").header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(bodyJson))
                .andExpect(status().isCreated()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static String courtTypeJson(String name) {
        return "{\"name\":\"%s\",\"nameEn\":\"%s\",\"color\":\"#00ff00\"}".formatted(name, name);
    }

    static String planJson(String name, String features, String active) {
        return ("{\"name\":\"%s\",\"monthlyPrice\":250000,\"yearlyPrice\":2500000,\"maxBranches\":3,"
                + "\"maxCourts\":12,\"features\":%s,\"active\":%s}").formatted(name, features, active);
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
