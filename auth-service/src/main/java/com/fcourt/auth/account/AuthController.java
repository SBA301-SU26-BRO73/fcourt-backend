package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountDtos.LoginRequest;
import com.fcourt.auth.account.AccountDtos.RefreshRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCourtOwnerRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCustomerRequest;
import com.fcourt.auth.account.AccountDtos.RegistrationResponse;
import com.fcourt.auth.account.AccountDtos.UpdateProfileRequest;
import com.fcourt.auth.account.AccountDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public routes: register, login, refresh, logout. Profile needs a valid access token. */
@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService auth;
    private final SessionService sessions;

    AuthController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @PostMapping("/register/customer")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) {
        return auth.registerCustomer(request);
    }

    @PostMapping(value = "/register/court-owner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResponse registerCourtOwner(@Valid @ModelAttribute RegisterCourtOwnerRequest request) {
        return auth.registerCourtOwner(request);
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return sessions.rotate(request.refreshToken());
    }

    /** Idempotent: an unknown or already revoked token is not an error. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        sessions.close(request.refreshToken());
    }

    @GetMapping("/profile")
    UserResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return auth.profile(userId(jwt));
    }

    @PatchMapping("/profile")
    UserResponse updateProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return auth.updateProfile(userId(jwt), request);
    }

    private static long userId(Jwt jwt) {
        // The JwtDecoder already guarantees a positive numeric subject.
        return Long.parseLong(jwt.getSubject());
    }
}
