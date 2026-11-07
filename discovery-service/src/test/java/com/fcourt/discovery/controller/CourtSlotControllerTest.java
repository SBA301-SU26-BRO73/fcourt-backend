package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.dto.response.CourtSlotResponse.Status;
import com.fcourt.discovery.dto.response.BlockingSlotResponse;
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
    private final MockMvc internalHttp = MockMvcBuilders.standaloneSetup(
            new CourtSlotInternalController(service, "test-token")).build();

    @Test
    void internalLockCheckReturnsOnlyBookingAndHoldOccupancy() throws Exception {
        when(service.blockingSlots(1, LocalDate.of(2030, 1, 1))).thenReturn(List.of(
                new BlockingSlotResponse(10, LocalTime.of(18, 0), LocalTime.of(18, 30), "BOOKED"),
                new BlockingSlotResponse(11, LocalTime.of(18, 30), LocalTime.of(19, 0), "HOLD")));
        internalHttp.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")
                        .header("X-Internal-Service-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("BOOKED"))
                .andExpect(jsonPath("$[1].status").value("HOLD"));
    }

    @Test
    void internalLockCheckRejectsMissingOrInvalidToken() throws Exception {
        internalHttp.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01"))
                .andExpect(status().isUnauthorized());
        internalHttp.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")
                        .header("X-Internal-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

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
    void serializesRestrictionOnlyWhenPresentWithTheFieldNamesOfTheFlutterContract() throws Exception {
        when(service.find(1, LocalDate.of(2030, 1, 1))).thenReturn(List.of(
                new CourtSlotResponse(1, LocalTime.of(18, 0), LocalTime.of(18, 30), new BigDecimal("75000"),
                        Status.OUT_OF_SERVICE, new CourtSlotResponse.Restriction("PRIVATE_EVENT", "Giai dau", null,
                        "0901234567")),
                new CourtSlotResponse(2, LocalTime.of(18, 30), LocalTime.of(19, 0), new BigDecimal("75000"),
                        Status.OUT_OF_SERVICE)));
        http.perform(get("/api/public/courts/1/slots?date=2030-01-01"))
                .andExpect(jsonPath("$[0].restriction.type").value("PRIVATE_EVENT"))
                .andExpect(jsonPath("$[0].restriction.publicReason").value("Giai dau"))
                .andExpect(jsonPath("$[0].restriction.contactPhone").value("0901234567"))
                .andExpect(jsonPath("$[0].restriction.contactName").doesNotExist())
                .andExpect(jsonPath("$[1].restriction").doesNotExist());
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
