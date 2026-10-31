package com.fcourt.facility.api;

import java.math.BigDecimal;
import java.time.Instant;

public record CourtResponse(long id, long branchId, String name, long courtTypeId, String description,
                            String imageUrl, BigDecimal defaultPricePerHour, String status,
                            Instant createdAt, Instant updatedAt) {

    public record StaffView(long id, long branchId, String status) { }

    public StaffView toStaffView() {
        return new StaffView(id, branchId, status);
    }
}
