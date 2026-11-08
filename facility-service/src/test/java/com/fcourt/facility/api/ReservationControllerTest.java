package com.fcourt.facility.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.facility.api.ReservationDtos.ReservationResponse;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.service.ReservationService;
import com.fcourt.facility.service.ReservationService.Result;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Service-token gate and request validation of the internal reservation endpoints (no database needed). */
class ReservationControllerTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final String BODY = """
            {"reservationId":"%s","branchId":3,"adminId":7,"courtIds":[1,2],
             "startAt":"2030-01-05T08:00:00","endAt":"2030-01-05T10:00:00"}
            """.formatted(ID);

    ReservationService service = mock(ReservationService.class);

    MockMvc mvc(String configuredToken) {
        return MockMvcBuilders.standaloneSetup(new ReservationController(service, configuredToken)).build();
    }

    static ReservationResponse response(State state) {
        return new ReservationResponse(ID, 3L, state, List.of(1L, 2L), LocalDateTime.of(2030, 1, 5, 8, 0),
                LocalDateTime.of(2030, 1, 5, 10, 0), null, null, null, null);
    }

    @Test
    void unconfiguredOrShortTokenFailsClosedWith503ForEveryRoute() throws Exception {
        for (String configured : new String[] {"", "too-short"}) {
            var mvc = mvc(configured);
            mvc.perform(post("/api/internal/court-reservations").header("X-Internal-Service-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/internal/court-reservations/{id}/commit", ID).header("X-Internal-Service-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/internal/court-reservations/{id}/rollback", ID)
                    .header("X-Internal-Service-Token", TOKEN)).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/internal/court-reservations/{id}", ID).header("X-Internal-Service-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
        }
        verifyNoInteractions(service);
    }

    @Test
    void missingOrWrongTokenIsForbiddenAndNeverReachesTheService() throws Exception {
        var mvc = mvc(TOKEN);
        mvc.perform(post("/api/internal/court-reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations").header("X-Internal-Service-Token", TOKEN + "x")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations/{id}/commit", ID)).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations/{id}/rollback", ID).header("X-Internal-Service-Token", "nope"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/internal/court-reservations/{id}", ID)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void validTokenReservesWith201AndReplaysWith200() throws Exception {
        var mvc = mvc(TOKEN);
        when(service.reserve(any())).thenReturn(new Result(response(State.RESERVED), true),
                new Result(response(State.RESERVED), false));
        var request = post("/api/internal/court-reservations").header("X-Internal-Service-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
        mvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("RESERVED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void commitRollbackAndStatusDelegateWithAValidToken() throws Exception {
        var mvc = mvc(TOKEN);
        when(service.commit(ID)).thenReturn(response(State.COMMITTED));
        when(service.rollback(ID)).thenReturn(response(State.CANCELLED));
        when(service.get(ID)).thenReturn(response(State.COMMITTED));
        mvc.perform(post("/api/internal/court-reservations/{id}/commit", ID).header("X-Internal-Service-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMMITTED"));
        mvc.perform(post("/api/internal/court-reservations/{id}/rollback", ID).header("X-Internal-Service-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(get("/api/internal/court-reservations/{id}", ID).header("X-Internal-Service-Token", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void malformedReserveBodiesAreRejectedAfterTheTokenCheck() throws Exception {
        var mvc = mvc(TOKEN);
        String noCourts = BODY.replace("[1,2]", "[]");
        String badTtl = BODY.replace("\"courtIds\"", "\"ttlSeconds\":5,\"courtIds\"");
        for (String body : new String[] {noCourts, badTtl, "{}"}) {
            mvc.perform(post("/api/internal/court-reservations").header("X-Internal-Service-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
