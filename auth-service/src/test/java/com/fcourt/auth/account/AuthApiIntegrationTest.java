package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Register, login, refresh and role guard end to end: real Spring Security chain, tokens signed by
 * this service and validated against its own JWKS endpoint, Postgres and Flyway. Needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class AuthApiIntegrationTest {
    static final String PASSWORD = "S3cret-pass";
    static final AtomicLong PHONE = new AtomicLong(900_000_000L);

    // Singleton container: it must outlive the cached Spring context, so Ryuk stops it at JVM exit.
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    static final int PORT = freePort();

    static {
        DATABASE.start();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean DocumentStorage storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("server.port", () -> PORT);
        // The service validates the tokens it issues through its own public JWKS endpoint.
        registry.add("auth.jwt.jwk-set-uri", () -> "http://localhost:" + PORT + "/.well-known/jwks.json");
        registry.add("auth.jwt.issuer", () -> "http://localhost:" + PORT);
        registry.add("auth.events.enabled", () -> "false");
    }

    @Test
    void customerRegistersLogsInAndReadsTheirProfileWithTheirOwnToken() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        var registered = body(mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email, phone)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE")));

        mvc.perform(get("/api/auth/profile").header("Authorization", "Bearer " + registered.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(phone));

        var login = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isOk()));
        assertThat(login.get("refreshToken").asText()).isNotEqualTo(registered.get("refreshToken").asText());
        mvc.perform(get("/api/auth/profile").header("Authorization", "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndDuplicateRegistrationAreRefused() throws Exception {
        String email = uniqueEmail();
        register(email, uniquePhone());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email.toUpperCase(), uniquePhone())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Email is already registered"));
    }

    @Test
    void profileAndRoleGuardedRoutesNeedAValidTokenAndTheRightRole() throws Exception {
        var customer = register(uniqueEmail(), uniquePhone());

        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/profile").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/super-admin/court-admins/pending")
                        .header("Authorization", "Bearer " + customer.get("accessToken").asText()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshRotatesTheTokenAndReplayingAnOldOneKillsTheWholeSession() throws Exception {
        var session = register(uniqueEmail(), uniquePhone());
        String first = session.get("refreshToken").asText();

        var rotated = body(mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(first)))
                .andExpect(status().isOk()));
        String second = rotated.get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);

        // Replaying the first token is treated as theft: the rotated token dies with it.
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(first)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(second)))
                .andExpect(status().isUnauthorized());
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL""", Integer.class,
                session.get("user").get("id").asLong());
        assertThat(live).isZero();
    }

    @Test
    void refreshTokensAreStoredHashedNotInTheClear() throws Exception {
        var session = register(uniqueEmail(), uniquePhone());
        String token = session.get("refreshToken").asText();

        Integer clear = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, token);
        Integer hashed = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, SessionService.hash(token));

        assertThat(clear).isZero();
        assertThat(hashed).isEqualTo(1);
    }

    @Test
    void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
        String token = register(uniqueEmail(), uniquePhone()).get("refreshToken").asText();

        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(token)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(token)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aLoggedOutTokenPresentedAgainIsRefusedWithoutSigningOutOtherDevices() throws Exception {
        String email = uniqueEmail();
        String loggedOut = register(email, uniquePhone()).get("refreshToken").asText();
        String otherDevice = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, PASSWORD))).andExpect(status().isOk())).get("refreshToken").asText();
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(loggedOut)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(loggedOut)))
                .andExpect(status().isUnauthorized());
        // Logout is not a rotation, so presenting the dead token is not treated as theft.
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(otherDevice)))
                .andExpect(status().isOk());
    }

    @Test
    void lockedAccountCannotRefreshOrUseItsAccessTokenForTheProfile() throws Exception {
        var session = register(uniqueEmail(), uniquePhone());
        long id = session.get("user").get("id").asLong();
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", id);

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.get("refreshToken").asText())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Account is locked"));
        mvc.perform(get("/api/auth/profile")
                        .header("Authorization", "Bearer " + session.get("accessToken").asText()))
                .andExpect(status().isForbidden());
        // The refusal committed: the session is revoked even after an unlock.
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", id);
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void courtOwnerRegistersPendingWithDocumentsAndCanSignInOnlyAfterApproval() throws Exception {
        String email = uniqueEmail();
        when(storage.store(any(), org.mockito.ArgumentMatchers.startsWith("legal/")))
                .thenReturn("https://res.cloudinary.com/legal.pdf");
        when(storage.store(any(), org.mockito.ArgumentMatchers.startsWith("court/")))
                .thenReturn("https://res.cloudinary.com/court.jpg");

        var registered = body(mvc.perform(multipart("/api/auth/register/court-owner")
                        .file(new MockMultipartFile("legalDocuments", "licence.pdf", "application/pdf", new byte[]{1}))
                        .file(new MockMultipartFile("courtImages", "front.jpg", "image/jpeg", new byte[]{1}))
                        .param("fullName", "Chu San").param("email", email)
                        .param("phone", uniquePhone()).param("password", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL")));
        long id = registered.get("id").asLong();

        assertThat(jdbc.queryForObject("SELECT role || '/' || status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("ADMIN/PENDING_APPROVAL");
        assertThat(jdbc.queryForList("SELECT doc_type FROM user_documents WHERE user_id = ? ORDER BY id",
                String.class, id)).containsExactly("LEGAL", "COURT_IMAGE");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Account is awaiting approval"));

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", id);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void forgotPasswordPublishesTheEventAndTheEmailedTokenResetsThePasswordExactlyOnce() throws Exception {
        String email = uniqueEmail();
        var session = register(email, uniquePhone());
        long id = session.get("user").get("id").asLong();

        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordService.FORGOT_MESSAGE));

        String payload = jdbc.queryForObject("""
                SELECT payload FROM auth_outbox
                WHERE aggregate_id = ? AND event_type = 'PasswordResetRequestedEvent'""", String.class, id);
        JsonNode data = json.readTree(payload).get("data");
        assertThat(data.get("email").asText()).isEqualTo(email);
        String token = data.get("token").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE token_hash = ?",
                Integer.class, token)).isZero();

        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(token, "Brand-new-pass1")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "Brand-new-pass1")))
                .andExpect(status().isOk());
        // Every session from before the reset is gone, and the token cannot be spent twice.
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(token, "Another-pass1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(PasswordService.INVALID_TOKEN));
    }

    @Test
    void forgotPasswordForAnUnknownEmailAnswersTheSameAndPublishesNothing() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordService.FORGOT_MESSAGE));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_outbox WHERE payload LIKE ?", Integer.class,
                "%" + email + "%")).isZero();
    }

    @Test
    void aNewResetRequestInvalidatesTheEarlierToken() throws Exception {
        String email = uniqueEmail();
        long id = register(email, uniquePhone()).get("user").get("id").asLong();
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        String first = json.readTree(jdbc.queryForObject("""
                SELECT payload FROM auth_outbox WHERE aggregate_id = ? AND event_type = 'PasswordResetRequestedEvent'
                ORDER BY created_at DESC LIMIT 1""", String.class, id)).get("data").get("token").asText();
        // Move the first request out of the cooldown window, then ask again.
        jdbc.update("UPDATE password_reset_tokens SET created_at = now() - interval '5 minutes' WHERE user_id = ?", id);
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());

        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(first, "Brand-new-pass1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordKeepsTheCallerSignedInAndRevokesOtherSessions() throws Exception {
        String email = uniqueEmail();
        register(email, uniquePhone());
        var otherDevice = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, PASSWORD))).andExpect(status().isOk()));
        var thisDevice = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, PASSWORD))).andExpect(status().isOk()));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/auth/password")
                        .header("Authorization", "Bearer " + thisDevice.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"Brand-new-pass1\"}"))
                .andExpect(status().isBadRequest());
        var changed = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/auth/password")
                        .header("Authorization", "Bearer " + thisDevice.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"Brand-new-pass1\"}".formatted(PASSWORD)))
                .andExpect(status().isOk()));

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(otherDevice.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(changed.get("refreshToken").asText())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "Brand-new-pass1")))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordNeedsAnAccessToken() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"Brand-new-pass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    static String resetBody(String token, String newPassword) {
        return "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword);
    }

    @Test
    void failedDocumentUploadLeavesNoHalfRegisteredOwner() throws Exception {
        String email = uniqueEmail();
        when(storage.store(any(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(com.fcourt.auth.common.ApiException.unavailable("Document storage is unavailable"));

        mvc.perform(multipart("/api/auth/register/court-owner")
                        .file(new MockMultipartFile("legalDocuments", "licence.pdf", "application/pdf", new byte[]{1}))
                        .param("fullName", "Chu San").param("email", email)
                        .param("phone", uniquePhone()).param("password", PASSWORD))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void jwksEndpointIsPublicAndExposesNoPrivateKeyMaterial() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    private JsonNode register(String email, String phone) throws Exception {
        return body(mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email, phone)))
                .andExpect(status().isCreated()));
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static String customerJson(String email, String phone) {
        return "{\"fullName\":\"An Nguyen\",\"email\":\"%s\",\"phone\":\"%s\",\"password\":\"%s\"}"
                .formatted(email, phone, PASSWORD);
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    static String refreshBody(String token) {
        return "{\"refreshToken\":\"%s\"}".formatted(token);
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.invalid";
    }

    static String uniquePhone() {
        return "0" + PHONE.incrementAndGet();
    }

    static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
