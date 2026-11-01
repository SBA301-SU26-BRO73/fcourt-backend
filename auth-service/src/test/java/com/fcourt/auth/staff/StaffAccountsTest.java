package com.fcourt.auth.staff;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

@Testcontainers
@SpringBootTest(
        properties = {"auth.events.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
class StaffAccountsTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockBean ManagedStaffBoundary boundary;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE users,staff_account_commands CASCADE");
        reset(boundary);
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,full_name,phone,role,status) OVERRIDING"
                    + " SYSTEM VALUE VALUES"
                    + " (1,'owner@fixture.test','hash','Owner','0912345678','ADMIN','ACTIVE')");
        jdbc.execute("SELECT setval('users_id_seq',100,true)");
    }

    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            admin() {
        return jwt().jwt(j -> j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    String key() {
        return "staff:1:" + UUID.randomUUID();
    }

    String createBody() {
        return "{\"branchId\":7,\"email\":\"Staff@fixture.test\",\"password\":\"StrongPassword123\",\"fullName\":\"New"
                   + " Staff\",\"phone\":\"0912345679\"}";
    }

    @Test
    void createHashesPasswordAndReplaysWithoutDuplicateAccount() throws Exception {
        String key = key();
        for (int n = 0; n < 2; n++)
            mvc.perform(
                            post("/api/internal/staff-accounts")
                                    .with(admin())
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(createBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("staff@fixture.test"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        String hash =
                jdbc.queryForObject("SELECT password_hash FROM users WHERE id=101", String.class);
        assertThat(new BCryptPasswordEncoder().matches("StrongPassword123", hash)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM staff_account_commands", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_account_action_logs WHERE"
                                    + " action='STAFF_CREATE'",
                                Integer.class))
                .isEqualTo(1);
        mvc.perform(
                        post("/api/internal/staff-accounts")
                                .with(admin())
                                .header("Idempotency-Key", key())
                                .contentType("application/json")
                                .content(createBody()))
                .andExpect(status().isConflict());
    }

    @Test
    void editAndDisableAreOwnedIdempotentAndProtectLockedAccounts() throws Exception {
        mvc.perform(
                        post("/api/internal/staff-accounts")
                                .with(admin())
                                .header("Idempotency-Key", key())
                                .contentType("application/json")
                                .content(createBody()))
                .andExpect(status().isCreated());
        String update = key();
        for (int n = 0; n < 2; n++)
            mvc.perform(
                            put("/api/internal/staff-accounts/101")
                                    .with(admin())
                                    .header("Idempotency-Key", update)
                                    .contentType("application/json")
                                    .content(
                                            "{\"branchId\":7,\"fullName\":\"Edited\",\"phone\":\"0912345681\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullName").value("Edited"));
        jdbc.update(
                "INSERT INTO refresh_tokens(user_id,token_hash,expires_at) VALUES (101,repeat('a',64),now()"
                    + " + interval '1 day')");
        String disable = key();
        for (int n = 0; n < 2; n++)
            mvc.perform(
                            delete("/api/internal/staff-accounts/101")
                                    .with(admin())
                                    .header("Idempotency-Key", disable))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_account_action_logs WHERE"
                                    + " action='STAFF_DISABLE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_tokens WHERE user_id=101 AND revoked_at"
                                    + " IS NULL",
                                Integer.class))
                .isZero();
        verify(boundary, atLeastOnce()).require(101, 0, "token");
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=101");
        mvc.perform(
                        delete("/api/internal/staff-accounts/101")
                                .with(admin())
                                .header("Idempotency-Key", key()))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidContactPasswordAndNonAdminCannotCreate() throws Exception {
        mvc.perform(
                        post("/api/internal/staff-accounts")
                                .with(admin())
                                .header("Idempotency-Key", key())
                                .contentType("application/json")
                                .content(createBody().replace("StrongPassword123", "short")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/internal/staff-accounts")
                                .with(
                                        jwt().jwt(j -> j.subject("1"))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_STAFF")))
                                .header("Idempotency-Key", key())
                                .contentType("application/json")
                                .content(createBody()))
                .andExpect(status().isForbidden());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM staff_account_commands", Integer.class))
                .isZero();
    }
}
