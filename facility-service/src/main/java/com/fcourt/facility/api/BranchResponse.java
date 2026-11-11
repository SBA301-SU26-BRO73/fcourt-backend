package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record BranchResponse(
        long id, long adminId, String name, String address, String ward, String district, String city,
        BigDecimal latitude, BigDecimal longitude, String phone,
        @JsonFormat(pattern = "HH:mm") LocalTime openTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closeTime,
        int slotDurationMinutes, String bankName, String bankBin, String bankAccountNumber,
        String bankAccountName, String status, int courtCount, Instant createdAt, Instant updatedAt,
        List<BranchImageResponse> images) {

    public BranchResponse withImages(List<BranchImageResponse> gallery) {
        return new BranchResponse(id, adminId, name, address, ward, district, city, latitude, longitude, phone,
                openTime, closeTime, slotDurationMinutes, bankName, bankBin, bankAccountNumber, bankAccountName,
                status, courtCount, createdAt, updatedAt, gallery);
    }

    /**
     * Customer-facing branch detail plus the bank details payment-service uses for the VietQR transfer.
     * Contact and opening hours are shown on the court detail page (parity with the old web).
     */
    public record Public(long id, String name, String address, String ward, String district, String city,
                         BigDecimal latitude, BigDecimal longitude, String phone,
                         @JsonFormat(pattern = "HH:mm") LocalTime openTime,
                         @JsonFormat(pattern = "HH:mm") LocalTime closeTime,
                         String bankName, String bankBin, String bankAccountNumber, String bankAccountName,
                         String status) { }

    public Public toPublic() {
        return new Public(id, name, address, ward, district, city, latitude, longitude, phone, openTime, closeTime,
                bankName, bankBin, bankAccountNumber, bankAccountName, status);
    }

    public record Status(long id, String status) { }
}
