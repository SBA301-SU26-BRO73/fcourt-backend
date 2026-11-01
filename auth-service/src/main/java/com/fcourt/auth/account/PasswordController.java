package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountDtos.ChangePasswordRequest;
import com.fcourt.auth.account.AccountDtos.ForgotPasswordRequest;
import com.fcourt.auth.account.AccountDtos.MessageResponse;
import com.fcourt.auth.account.AccountDtos.ResetPasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Forgot and reset are public (the caller cannot sign in); changing a password needs an access token. */
@RestController
@RequestMapping("/api/auth/password")
class PasswordController {
    private final PasswordService passwords;

    PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    MessageResponse forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        return new MessageResponse(passwords.forgot(request.email()));
    }

    @PostMapping("/reset")
    MessageResponse reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwords.reset(request.token(), request.newPassword());
        return new MessageResponse("Password has been reset. Please sign in again");
    }

    @PatchMapping
    AuthResponse change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        // The JwtDecoder already guarantees a positive numeric subject.
        return passwords.change(Long.parseLong(jwt.getSubject()), request.currentPassword(), request.newPassword());
    }
}
