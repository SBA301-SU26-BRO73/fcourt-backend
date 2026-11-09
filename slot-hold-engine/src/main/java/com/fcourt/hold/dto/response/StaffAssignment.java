package com.fcourt.hold.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffAssignment(long userId, long branchId, boolean active) {}
