package com.fcourt.auth.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.common.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class PasswordControllerTest {
    PasswordService passwords;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        passwords = mock(PasswordService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new PasswordController(passwords))
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
    void forgotAnswers202WithTheNeutralMessage() throws Exception {
        when(passwords.forgot("owner@demo.com")).thenReturn(PasswordService.FORGOT_MESSAGE);

        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@demo.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(PasswordService.FORGOT_MESSAGE));
    }

    @Test
    void forgotRejectsAMalformedEmail() throws Exception {
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        verify(passwords, never()).forgot(any());
    }

    @Test
    void resetPassesTheTokenAndNewPasswordToTheService() throws Exception {
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\",\"newPassword\":\"Brand-new-pass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(passwords).reset("abc", "Brand-new-pass1");
    }

    @Test
    void resetValidatesTokenAndPasswordLength() throws Exception {
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        verify(passwords, never()).reset(any(), any());
    }

    @Test
    void invalidTokenComesBackAsABadRequestProblem() throws Exception {
        org.mockito.Mockito.doThrow(ApiException.badRequest(PasswordService.INVALID_TOKEN))
                .when(passwords).reset(any(), any());

        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\",\"newPassword\":\"Brand-new-pass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(PasswordService.INVALID_TOKEN))
                .andExpect(jsonPath("$.instance").value("/api/auth/password/reset"));
    }

    @Test
    void changeUsesTheTokenSubjectAndReturnsTheFreshSession() throws Exception {
        authenticateAs(7);
        when(passwords.change(eq(7L), eq("Current-pass1"), eq("Brand-new-pass1")))
                .thenReturn(new AuthResponse("access", "refresh", "Bearer", 900, null));

        mvc.perform(patch("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Current-pass1\",\"newPassword\":\"Brand-new-pass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    void changeRequiresBothPasswords() throws Exception {
        authenticateAs(7);

        mvc.perform(patch("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        verify(passwords, never()).change(org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    void authenticateAs(long userId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(Long.toString(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
