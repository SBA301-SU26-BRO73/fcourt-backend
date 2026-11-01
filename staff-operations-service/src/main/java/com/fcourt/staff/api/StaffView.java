package com.fcourt.staff.api;

import java.time.Instant;
import java.util.List;

public record StaffView(long id, long userId, long branchId, String email, String fullName,
                        String phone, boolean active, Instant createdAt, Instant updatedAt,
                        List<StaffRequests.Shift> shifts) { }
