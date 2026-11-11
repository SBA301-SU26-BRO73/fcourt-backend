package com.fcourt.facility.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.facility.persistence.BranchStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BranchOwnerControllerTest {
  BranchStore branches = mock(BranchStore.class);
  MockMvc mvc = MockMvcBuilders.standaloneSetup(new BranchOwnerController(branches)).build();

  @Test
  void answersTheOwnerUserIdOfABranch() throws Exception {
    var branch = mock(BranchResponse.class);
    when(branch.adminId()).thenReturn(70L);
    when(branches.find(3)).thenReturn(Optional.of(branch));

    mvc.perform(get("/api/internal/branches/3/owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branchId").value(3))
        .andExpect(jsonPath("$.ownerId").value(70));
  }

  @Test
  void anUnknownBranchIs404() throws Exception {
    when(branches.find(4)).thenReturn(Optional.empty());

    mvc.perform(get("/api/internal/branches/4/owner")).andExpect(status().isNotFound());
  }
}
