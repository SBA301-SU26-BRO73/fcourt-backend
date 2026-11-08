package com.fcourt.booking;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

record AdminBookingFilter(
        @Positive Long branchId,
        @Positive Long courtId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @Pattern(regexp = "PENDING_PAYMENT|AWAITING_CONFIRMATION|CONFIRMED|CHECKED_IN|COMPLETED|CANCELLED") String status,
        @Size(max = 200) String search,
        @Pattern(regexp = "PENDING|CONFIRMED|REJECTED|CANCELLED|PAID") String paymentStatus,
        @Min(0) @Max(100000) Integer page,
        @Min(1) @Max(100) Integer size,
        @Pattern(regexp = "date|createdAt") String sortBy,
        @Pattern(regexp = "ASC|DESC") String direction) {

    @AssertTrue(message = "fromDate must not exceed toDate")
    public boolean isDateRangeValid() {
        return fromDate == null || toDate == null || !fromDate.isAfter(toDate);
    }

    int pageNumber() { return page == null ? 0 : page; }
    int pageSize() { return size == null ? 20 : size; }
    String sortColumn() { return "createdAt".equals(sortBy) ? "b.created_at" : "b.date"; }
    String sortDirection() { return "ASC".equals(direction) ? "ASC" : "DESC"; }
}
