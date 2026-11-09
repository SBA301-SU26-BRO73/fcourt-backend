package com.fcourt.hold.dto.response;

import com.fcourt.hold.dto.request.HoldRequest;

public record OnlineHoldReleaseInfo(String token, HoldRequest hold) {}
