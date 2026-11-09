package com.fcourt.notification.device;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/devices")
class DeviceController {
    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRegistration registration) {
        devices.register(userId(jwt), registration);
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        devices.unregister(userId(jwt), deviceId);
    }

    private long userId(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // Invalid subjects are unauthorized, regardless of request body contents.
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user subject");
    }
}
