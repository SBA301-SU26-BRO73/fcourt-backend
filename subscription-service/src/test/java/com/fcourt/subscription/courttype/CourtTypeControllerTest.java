package com.fcourt.subscription.courttype;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.ApiExceptionHandler;
import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Request binding, validation, status codes and the RFC 7807 error shape of the court type API. */
class CourtTypeControllerTest {
    CourtTypeService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CourtTypeService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CourtTypeController(service))
                .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
    }

    @Test
    void listReturnsAPageAndPassesFilterAndPaging() throws Exception {
        when(service.list(true, new Pagination(1, 20))).thenReturn(new PageResponse<>(
                List.of(courtType(1, "Football")), 1, 20, 21, 2));

        mvc.perform(get("/api/court-types").param("active", "true").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Football"))
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void absurdPagingIsClampedInsteadOfPassedThrough() throws Exception {
        when(service.list(null, new Pagination(0, 100))).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        mvc.perform(get("/api/court-types").param("page", "-4").param("size", "100000"))
                .andExpect(status().isOk());

        verify(service).list(null, new Pagination(0, 100));
    }

    @Test
    void getReturnsTheTypeAndAMissingOneIsAProblem() throws Exception {
        when(service.get(1)).thenReturn(courtType(1, "Football"));
        when(service.get(2)).thenThrow(ApiException.notFound("Court type not found"));

        mvc.perform(get("/api/court-types/1")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(get("/api/court-types/2"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Court type not found"))
                .andExpect(jsonPath("$.instance").value("/api/court-types/2"));
    }

    @Test
    void anIdThatIsNotANumberIsABadRequest() throws Exception {
        mvc.perform(get("/api/court-types/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(any())).thenReturn(courtType(3, "Padel"));

        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Padel\",\"nameEn\":\"Padel\",\"color\":\"#00ff00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    void createWithABlankOrOversizedNameIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("name is required"));
        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("name must be at most 100 characters"));
        verify(service, never()).create(any());
    }

    @Test
    void aDuplicateNameComesBackAs409() throws Exception {
        when(service.create(any())).thenThrow(ApiException.conflict("Court type name already exists"));

        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Padel\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Court type name already exists"));
    }

    @Test
    void updateUsesThePathIdAndDeleteAnswers204() throws Exception {
        when(service.update(eq(4L), any())).thenReturn(courtType(4, "Tennis"));

        mvc.perform(put("/api/court-types/4").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tennis\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tennis"));
        mvc.perform(delete("/api/court-types/4")).andExpect(status().isNoContent());

        verify(service).delete(4);
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/court-types").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    static CourtTypeResponse courtType(long id, String name) {
        return new CourtTypeResponse(id, name, null, null, null, null, true,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
