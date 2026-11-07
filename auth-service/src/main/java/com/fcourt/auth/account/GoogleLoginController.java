package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GoogleLoginController {
    private final GoogleLoginService google;

    GoogleLoginController(GoogleLoginService google) {
        this.google = google;
    }

    @PostMapping("/api/auth/google")
    AuthResponse login(@Valid @RequestBody GoogleRequest request) {
        return google.login(request.idToken());
    }

    record GoogleRequest(@NotBlank String idToken) { }
}
