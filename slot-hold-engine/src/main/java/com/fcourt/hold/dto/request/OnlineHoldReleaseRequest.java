package com.fcourt.hold.dto.request;

import jakarta.validation.constraints.NotBlank;

public record OnlineHoldReleaseRequest(@NotBlank String holdHash) {}
