package com.fcourt.notification.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRegistration(
        @NotBlank @Size(max = 128) String deviceId,
        @NotBlank @Pattern(regexp = "ANDROID") @Size(max = 16) String platform,
        @NotBlank @Size(max = 512) String token) {}
