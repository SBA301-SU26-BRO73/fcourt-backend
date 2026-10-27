package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.dto.response.CourtSlotResponse.Status;
import com.fcourt.discovery.service.CourtSlotService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourtSlotControllerTest {
    private final CourtSlotService service = mock(CourtSlotService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new CourtSlotController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                            .build())).build();

    @Test
    void publicArrayMatchesHoldEngineAndDisablesCaching() throws Exception {
        when(service.find(1, LocalDate.of(2030, 1, 1))).thenReturn(List.of(new CourtSlotResponse(1,
                LocalTime.of(18, 0), LocalTime.of(18, 30), new BigDecimal("75000"), Status.AVAILABLE)));
        http.perform(get("/api/public/courts/1/slots?date=2030-01-01"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].slotId").value(1))
                .andExpect(jsonPath("$[0].startTime").value("18:00:00"))
                .andExpect(jsonPath("$[0].price").value(75000))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    void rejectsMissingAndInvalidDateOrId() throws Exception {
        for (String url : List.of("/1/slots", "/1/slots?date=invalid", "/abc/slots?date=2030-01-01",
                "/1/slots?date=2030-02-30")) {
            http.perform(get("/api/public/courts" + url)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void preservesMissingCourtAndDependencyFailureStatus() throws Exception {
        when(service.find(anyLong(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        http.perform(get("/api/public/courts/1/slots?date=2030-01-01")).andExpect(status().isNotFound());
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(service).find(anyLong(), any());
        http.perform(get("/api/public/courts/1/slots?date=2030-01-01")).andExpect(status().isServiceUnavailable());
    }
}
