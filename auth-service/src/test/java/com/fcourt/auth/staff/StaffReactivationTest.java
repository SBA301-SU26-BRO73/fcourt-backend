package com.fcourt.auth.staff;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers @SpringBootTest(properties={"auth.events.enabled=false","spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
class StaffReactivationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@MockBean ManagedStaffBoundary boundary;
    @BeforeEach void seed(){
        jdbc.execute("TRUNCATE users CASCADE");reset(boundary);
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone,role,status) OVERRIDING SYSTEM VALUE VALUES (1,'admin@fixture.test','fixture-hash','Owner','0912345678','ADMIN','ACTIVE'),(100,'staff@fixture.test','fixture-hash','Staff','0912345679','STAFF','INACTIVE'),(300,'locked@fixture.test','fixture-hash','Locked','0912345680','STAFF','LOCKED')");
    }
    @Test void ownerReactivationIsAuditedOnceAndReplayCannotResetAccount()throws Exception{
        String key="staff:1:"+UUID.randomUUID();
        for(int i=0;i<2;i++)mvc.perform(post("/api/internal/staff-accounts/100/reactivate").with(jwt().jwt(j->j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
          .header("Idempotency-Key",key).contentType("application/json").content("{\"branchId\":7}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_reactivation_commands",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account_action_logs WHERE action='STAFF_REACTIVATE'",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=100");
        mvc.perform(post("/api/internal/staff-accounts/100/reactivate").with(jwt().jwt(j->j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
          .header("Idempotency-Key",key).contentType("application/json").content("{\"branchId\":7}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=100",String.class)).isEqualTo("LOCKED");
    }
    @Test void lockedTargetForeignBranchAndInvalidRoleAreRejected()throws Exception{
        mvc.perform(post("/api/internal/staff-accounts/300/reactivate").with(jwt().jwt(j->j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
          .header("Idempotency-Key","staff:1:"+UUID.randomUUID()).contentType("application/json").content("{\"branchId\":7}"))
          .andExpect(status().isConflict());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"foreign branch")).when(boundary).require(eq(100L),eq(8L),anyString());
        mvc.perform(post("/api/internal/staff-accounts/100/reactivate").with(jwt().jwt(j->j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
          .header("Idempotency-Key","staff:1:"+UUID.randomUUID()).contentType("application/json").content("{\"branchId\":8}"))
          .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/staff-accounts/100/reactivate").with(jwt().jwt(j->j.subject("100")).authorities(new SimpleGrantedAuthority("ROLE_STAFF")))
          .header("Idempotency-Key","staff:100:"+UUID.randomUUID()).contentType("application/json").content("{\"branchId\":7}"))
          .andExpect(status().isForbidden());
    }
    @Test void liveIdentityReadRequiresAuthenticationAndNeverReturnsCredentials()throws Exception{
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(jwt().jwt(j->j.subject("100"))))
          .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(100)).andExpect(jsonPath("$.status").value("INACTIVE"))
          .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
