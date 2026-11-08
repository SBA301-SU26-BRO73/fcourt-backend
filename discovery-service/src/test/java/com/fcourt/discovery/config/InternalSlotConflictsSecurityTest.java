package com.fcourt.discovery.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.discovery.controller.CourtSlotInternalController;
import com.fcourt.discovery.service.CourtSlotService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The facility lock check runs through the real security chain (a standalone controller test cannot see it denied). */
@WebMvcTest(CourtSlotInternalController.class)
@Import(DiscoverySecurity.class)
@TestPropertySource(properties = {
        "discovery.auth.jwk-set-uri=http://localhost:1/jwks.json",
        "discovery.auth.issuer=http://localhost:1",
        "discovery.auth.audience=fcourt",
        "discovery.internal.slot-token=0123456789abcdef0123456789abcdef"})
class InternalSlotConflictsSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourtSlotService service;

    @Test
    void facilityReachesTheRouteWithTheInternalTokenAndNothingElseInternalIsOpen() throws Exception {
        when(service.blockingSlots(1, LocalDate.of(2030, 1, 1))).thenReturn(List.of());
        mvc.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")
                .header("X-Internal-Service-Token", "0123456789abcdef0123456789abcdef")).andExpect(status().isOk());
        mvc.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")
                .header("X-Internal-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/courts/1/other")).andExpect(status().is4xxClientError());
    }
}
