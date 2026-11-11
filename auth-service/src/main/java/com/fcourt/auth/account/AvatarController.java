package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.UserResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The caller's own avatar: PUT multipart {@code file} to set or replace it, DELETE to clear it. Both return the profile. */
@RestController
@RequestMapping("/api/auth/profile/avatar")
class AvatarController {
    private final AvatarService avatars;
    private final AuthService auth;

    AvatarController(AvatarService avatars, AuthService auth) {
        this.avatars = avatars;
        this.auth = auth;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    UserResponse set(@AuthenticationPrincipal Jwt jwt, @RequestPart(name = "file", required = false) MultipartFile file) {
        long userId = Long.parseLong(jwt.getSubject());
        avatars.set(userId, file);
        return auth.profile(userId);
    }

    @DeleteMapping
    UserResponse clear(@AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        avatars.clear(userId);
        return auth.profile(userId);
    }
}
