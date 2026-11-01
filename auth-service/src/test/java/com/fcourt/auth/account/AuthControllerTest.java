package com.fcourt.auth.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountDtos.RegistrationResponse;
import com.fcourt.auth.account.AccountDtos.UserResponse;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.common.ApiExceptionHandler;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Request binding, validation and the RFC 7807 error shape of the public auth API. */
class AuthControllerTest {
    AuthService auth;
    SessionService sessions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        sessions = mock(SessionService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, sessions))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerRegistrationReturns201WithTheSession() throws Exception {
        when(auth.registerCustomer(any())).thenReturn(session());

        mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("customer@demo.com", "0909000001", "S3cret-pass")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value("customer@demo.com"));
    }

    @Test
    void invalidRegistrationFieldsAreListedAndNothingIsRegistered() throws Exception {
        mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("not-an-email", "12345", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid request fields"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')].message").value("email is invalid"))
                .andExpect(jsonPath("$.errors[?(@.field=='phone')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='password')].message")
                        .value("password must be 8 to 72 characters"));
        verify(auth, never()).registerCustomer(any());
    }

    @Test
    void passwordsLongerThanBcryptCanHashAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("customer@demo.com", "0909000001", "x".repeat(73))))
                .andExpect(status().isBadRequest());
        verify(auth, never()).registerCustomer(any());
    }

    /** 30 characters but 90 UTF-8 bytes: BCrypt would throw, so validation must refuse it first. */
    @Test
    void accentedPasswordsOverBcryptsByteLimitAreRejectedEvenWhenShortInCharacters() throws Exception {
        mvc.perform(post("/api/auth/register/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("customer@demo.com", "0909000001", "ệ".repeat(30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='password')].message")
                        .value(AccountDtos.PASSWORD_BYTES_MESSAGE));
        mvc.perform(multipart("/api/auth/register/court-owner")
                        .file(new MockMultipartFile("legalDocuments", "licence.pdf", "application/pdf", new byte[]{1}))
                        .param("fullName", "Chu San").param("email", "owner@demo.com")
                        .param("phone", "0909000003").param("password", "ệ".repeat(30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        verify(auth, never()).registerCustomer(any());
        verify(auth, never()).registerCourtOwner(any());
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    @Test
    void badCredentialsComeBackAsAProblemWithTheRequestPath() throws Exception {
        when(auth.login(any())).thenThrow(ApiException.unauthorized("Invalid email or password"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@demo.com\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"))
                .andExpect(jsonPath("$.instance").value("/api/auth/login"));
    }

    @Test
    void loginRequiresEmailAndPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void refreshExchangesTheRefreshTokenForANewPair() throws Exception {
        when(sessions.rotate("old-refresh")).thenReturn(session());

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    void refreshRequiresAToken() throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"));
        verify(sessions, never()).rotate(any());
    }

    @Test
    void logoutRevokesTheTokenAndAnswers204() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isNoContent());

        verify(sessions).close("old-refresh");
    }

    @Test
    void courtOwnerRegistrationAcceptsMultipartFormWithDocuments() throws Exception {
        when(auth.registerCourtOwner(any())).thenReturn(new RegistrationResponse(9, "PENDING_APPROVAL", "submitted"));

        mvc.perform(multipart("/api/auth/register/court-owner")
                        .file(new MockMultipartFile("legalDocuments", "licence.pdf", "application/pdf", new byte[]{1}))
                        .file(new MockMultipartFile("legalDocuments", "id.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("courtImages", "front.jpg", "image/jpeg", new byte[]{1}))
                        .param("fullName", "Chu San").param("email", "owner@demo.com")
                        .param("phone", "0909000003").param("password", "S3cret-pass"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        var captor = org.mockito.ArgumentCaptor.forClass(AccountDtos.RegisterCourtOwnerRequest.class);
        verify(auth).registerCourtOwner(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().legalDocuments()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().courtImages()).hasSize(1);
    }

    @Test
    void courtOwnerRegistrationWithoutLegalDocumentsIsRejectedByValidation() throws Exception {
        mvc.perform(multipart("/api/auth/register/court-owner")
                        .param("fullName", "Chu San").param("email", "owner@demo.com")
                        .param("phone", "0909000003").param("password", "S3cret-pass"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("legalDocuments"));
        verify(auth, never()).registerCourtOwner(any());
    }

    @Test
    void profileIsReadForTheSubjectOfTheAccessToken() throws Exception {
        authenticateAs(7);
        when(auth.profile(7)).thenReturn(user());

        mvc.perform(get("/api/auth/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void profileUpdateValidatesThePhoneAndUsesTheTokenSubject() throws Exception {
        authenticateAs(7);
        when(auth.updateProfile(eq(7L), any())).thenReturn(user());

        mvc.perform(patch("/api/auth/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Name\",\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        mvc.perform(patch("/api/auth/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Name\",\"phone\":\"+84911222333\"}"))
                .andExpect(status().isOk());

        verify(auth).updateProfile(eq(7L), any());
    }

    void authenticateAs(long userId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(Long.toString(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    static String customerJson(String email, String phone, String password) {
        return "{\"fullName\":\"An Nguyen\",\"email\":\"%s\",\"phone\":\"%s\",\"password\":\"%s\"}"
                .formatted(email, phone, password);
    }

    static UserResponse user() {
        return new UserResponse(7, "customer@demo.com", "An Nguyen", "0909000001", "CUSTOMER", "ACTIVE",
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }

    static AuthResponse session() {
        return new AuthResponse("access", "refresh", "Bearer", 900, user());
    }
}
