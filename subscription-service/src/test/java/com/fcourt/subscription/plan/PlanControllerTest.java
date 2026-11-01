package com.fcourt.subscription.plan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.ApiExceptionHandler;
import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Request binding, validation, status codes and the RFC 7807 error shape of the plan API. */
class PlanControllerTest {
    static final String VALID = "{\"name\":\"Pro\",\"monthlyPrice\":100000,\"yearlyPrice\":1000000,"
            + "\"maxBranches\":2,\"maxCourts\":10,\"features\":[\"Reports\"],\"popular\":true}";

    PlanService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PlanService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(service))
                .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
    }

    @Test
    void listReturnsAPage() throws Exception {
        when(service.list(new Pagination(0, 10))).thenReturn(new PageResponse<>(List.of(plan(1, "Basic")), 0, 10, 1, 1));

        mvc.perform(get("/api/subscription-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Basic"))
                .andExpect(jsonPath("$.content[0].features[0]").value("Reports"));
    }

    @Test
    void allIsAPlainArrayOfPlansOnSaleAndIsNotConfusedWithAnId() throws Exception {
        when(service.listActive()).thenReturn(List.of(plan(1, "Basic"), plan(2, "Pro")));

        mvc.perform(get("/api/subscription-plans/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].name").value("Pro"));
        verify(service, never()).get(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void getReturnsThePlanAndAMissingOneIsAProblem() throws Exception {
        when(service.get(1)).thenReturn(plan(1, "Basic"));
        when(service.get(2)).thenThrow(ApiException.notFound("Subscription plan not found"));

        mvc.perform(get("/api/subscription-plans/1")).andExpect(status().isOk()).andExpect(jsonPath("$.maxCourts").value(10));
        mvc.perform(get("/api/subscription-plans/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Subscription plan not found"));
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(any())).thenReturn(plan(5, "Pro"));

        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void createListsEveryInvalidField() throws Exception {
        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"monthlyPrice\":-1,\"maxBranches\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("name is required"))
                .andExpect(jsonPath("$.errors[?(@.field=='monthlyPrice')].message")
                        .value("monthlyPrice must be positive; omit it if the plan is not sold monthly"))
                .andExpect(jsonPath("$.errors[?(@.field=='maxBranches')].message")
                        .value("maxBranches must be at least 1"))
                .andExpect(jsonPath("$.errors[?(@.field=='maxCourts')].message").value("maxCourts is required"));
        verify(service, never()).create(any());
    }

    @Test
    void aZeroPriceIsRejectedBecauseTheFreeTierIsTheStarterNotAPlan() throws Exception {
        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Free\",\"monthlyPrice\":0,\"yearlyPrice\":0,\"maxBranches\":1,"
                                + "\"maxCourts\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='monthlyPrice')].message")
                        .value("monthlyPrice must be positive; omit it if the plan is not sold monthly"))
                .andExpect(jsonPath("$.errors[?(@.field=='yearlyPrice')].message")
                        .value("yearlyPrice must be positive; omit it if the plan is not sold yearly"));
        verify(service, never()).create(any());
    }

    @Test
    void tooManyOrTooLongFeaturesAreRejected() throws Exception {
        String many = "\"" + String.join("\",\"", java.util.Collections.nCopies(31, "x")) + "\"";
        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pro\",\"maxBranches\":1,\"maxCourts\":1,\"features\":[" + many + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("features"));
        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pro\",\"maxBranches\":1,\"maxCourts\":1,\"features\":[\""
                                + "y".repeat(256) + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateNameComesBackAs409() throws Exception {
        when(service.create(any())).thenThrow(ApiException.conflict("Subscription plan name already exists"));

        mvc.perform(post("/api/subscription-plans").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
    }

    @Test
    void updateUsesThePathIdAndDeleteAnswers204() throws Exception {
        when(service.update(eq(4L), any())).thenReturn(plan(4, "Pro"));

        mvc.perform(put("/api/subscription-plans/4").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4));
        mvc.perform(delete("/api/subscription-plans/4")).andExpect(status().isNoContent());

        verify(service).delete(4);
    }

    static SubscriptionPlanResponse plan(long id, String name) {
        return new SubscriptionPlanResponse(id, name, null, 100_000L, 1_000_000L, 10, 2, true, null, false,
                List.of("Reports"), OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
