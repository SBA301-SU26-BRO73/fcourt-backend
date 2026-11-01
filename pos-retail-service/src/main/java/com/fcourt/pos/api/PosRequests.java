package com.fcourt.pos.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PosRequests {
    private PosRequests() { }
    public record Product(@Positive long branchId, @NotBlank @Size(max = 150) String name,
                          @Size(max = 30) String unit, @NotBlank @Size(max = 60) String category,
                          @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
                          @Min(0) @Max(1000000000) int stockQuantity,
                          @Size(max = 2000) @Pattern(regexp = "https://[^\\s]+") String imageUrl) { }
    public record ProductUpdate(@NotBlank @Size(max = 150) String name, @Size(max = 30) String unit,
                                @NotBlank @Size(max = 60) String category,
                                @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
                                @NotNull Boolean active, @Size(max = 2000) @Pattern(regexp = "https://[^\\s]+") String imageUrl) { }
    public record StockAdjustment(@Min(-1000000000) @Max(1000000000) int delta,
                                   @NotBlank @Size(max = 500) String reason) { }
    public record Item(@Positive long productId, @Min(1) @Max(10000) int quantity) { }
    public record Order(@NotNull @Pattern(regexp = "CASH|BOOKING") String paymentMethod,
                        @Positive Long bookingId, @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) { }
    public record SettledCharge(@NotBlank @Size(max = 40) String orderCode,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) { }
    public record Settlement(@NotNull UUID eventId, @Positive long bookingId, @Positive long branchId,
                              @NotNull Instant paidAt,
                              @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid SettledCharge> orders) { }
}
