package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

public record BranchResponse(
        long id, long adminId, String name, String address, String ward, String district, String city,
        BigDecimal latitude, BigDecimal longitude, String phone,
        @JsonFormat(pattern = "HH:mm") LocalTime openTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closeTime,
        int slotDurationMinutes, String bankName, String bankBin, String bankAccountNumber,
        String bankAccountName, String status, int courtCount, Instant createdAt, Instant updatedAt) {

    /** Bank details used by payment-service to build the VietQR transfer. */
    public record Public(long id, String name, String address, String city, String bankName, String bankBin,
                         String bankAccountNumber, String bankAccountName, String status) { }

    public Public toPublic() {
        return new Public(id, name, address, city, bankName, bankBin, bankAccountNumber, bankAccountName, status);
    }

    public record Status(long id, String status) { }
}
