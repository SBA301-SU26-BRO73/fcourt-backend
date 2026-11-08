package com.fcourt.auth.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.auth.account.AccountDtos.UserResponse;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.common.ApiExceptionHandler;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AvatarControllerTest {
    AvatarService avatars;
    AuthService auth;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        avatars = mock(AvatarService.class);
        auth = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AvatarController(avatars, auth))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    static UserResponse profile(String avatar) {
        return new UserResponse(7, "a@b.c", "An", "0909000001", "CUSTOMER", "ACTIVE", OffsetDateTime.now(), avatar);
    }

    @Test
    void putUsesTheTokenSubjectAndReturnsTheProfileWithTheAvatar() throws Exception {
        when(auth.profile(7)).thenReturn(profile("https://res.cloudinary.com/demo/image/upload/v1/fcourt/avatars/7/a.png"));
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/auth/profile/avatar").file(file).with(request -> {
            request.setMethod("PUT");
            return request;
        })).andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value("https://res.cloudinary.com/demo/image/upload/v1/fcourt/avatars/7/a.png"));
        verify(avatars).set(eq(7L), any());
    }

    @Test
    void deleteClearsTheAvatarOfTheCaller() throws Exception {
        when(auth.profile(7)).thenReturn(profile(null));
        mvc.perform(delete("/api/auth/profile/avatar")).andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
        verify(avatars).clear(7L);
    }

    @Test
    void validationErrorsSurfaceAsBadRequest() throws Exception {
        doThrow(ApiException.badRequest("Avatar accepts only JPEG, PNG or WebP images")).when(avatars).set(eq(7L), any());
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
        mvc.perform(multipart("/api/auth/profile/avatar").file(file).with(request -> {
            request.setMethod("PUT");
            return request;
        })).andExpect(status().isBadRequest());
    }
}
