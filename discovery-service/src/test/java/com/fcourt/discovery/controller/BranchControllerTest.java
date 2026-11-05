package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
import com.fcourt.discovery.exception.ApiExceptionHandler;
import com.fcourt.discovery.service.BranchSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BranchControllerTest {
    private final BranchSearchService service = mock(BranchSearchService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new BranchController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void bindsCombinedFiltersAndDisablesHttpCaching() throws Exception {
        when(service.search(any())).thenReturn(new BranchSearchResponse(List.of(), 1, 5, 0, 0));
        http.perform(get("/api/public/branches").param("city", "HCM").param("district", "1")
                        .param("courtTypeId", "2").param("minPrice", "50000").param("maxPrice", "100000")
                        .param("hasAvailableSlotsToday", "true").param("timeSlot", "18:00 - 20:00")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(service).search(new BranchSearchRequest(null, "HCM", "1", 2L, new BigDecimal("50000"),
                new BigDecimal("100000"), null, null, 1, 5, true, "18:00 - 20:00"));
    }

    @Test
    void rejectsMalformedReversedEqualAndOvernightRanges() throws Exception {
        for (String range : List.of("", "18:00", "25:00-26:00", "18:60-19:00", "18:00:00-19:00:00",
                "20:00 - 18:00", "18:00-18:00", "23:00-00:30")) {
            http.perform(get("/api/public/branches").param("timeSlot", range)).andExpect(status().isBadRequest());
        }
        http.perform(get("/api/public/branches").param("hasAvailableSlotsToday", "maybe"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/public/branches").param("minPrice", "100").param("maxPrice", "50"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void falseFlagPreservesBasicSearchAndRangeAloneDisablesCaching() throws Exception {
        when(service.search(any())).thenReturn(new BranchSearchResponse(List.of(), 0, 20, 0, 0));
        http.perform(get("/api/public/branches").param("hasAvailableSlotsToday", "false"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Cache-Control"));
        http.perform(get("/api/public/branches").param("timeSlot", "18:00-19:00"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
