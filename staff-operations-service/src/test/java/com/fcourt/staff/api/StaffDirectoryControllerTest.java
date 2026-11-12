package com.fcourt.staff.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.staff.persistence.StaffStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The service-token gate and the answer of the staff directory used by notification-service. */
class StaffDirectoryControllerTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String PATH = "/api/internal/branches/3/staff-user-ids";

    StaffStore store = mock(StaffStore.class);

    MockMvc mvc(String configured) {
        return MockMvcBuilders.standaloneSetup(new StaffDirectoryController(store, configured)).build();
    }

    @Test
    void answersTheActiveStaffUserIdsWithTheToken() throws Exception {
        when(store.activeUserIds(3)).thenReturn(List.of(81L, 82L));

        mvc(TOKEN).perform(get(PATH).header("X-Internal-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(3))
                .andExpect(jsonPath("$.userIds[0]").value(81))
                .andExpect(jsonPath("$.userIds[1]").value(82));
    }

    @Test
    void failsClosedWhenNoTokenIsConfigured() throws Exception {
        for (String configured : new String[] {"", "too-short"}) {
            mvc(configured).perform(get(PATH).header("X-Internal-Service-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
        }
        verifyNoInteractions(store);
    }

    @Test
    void aMissingOrWrongTokenIsForbidden() throws Exception {
        mvc(TOKEN).perform(get(PATH)).andExpect(status().isForbidden());
        mvc(TOKEN).perform(get(PATH).header("X-Internal-Service-Token", "x".repeat(32))).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
}
