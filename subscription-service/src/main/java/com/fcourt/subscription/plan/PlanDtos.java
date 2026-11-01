package com.fcourt.subscription.plan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response shapes of the subscription plan API (see README, subscription-service). */
public final class PlanDtos {
    private PlanDtos() {
    }

    /**
     * Full replacement on PUT. Prices are in VND; null means the plan is not sold for that cycle.
     * {@code popular} defaults to false. {@code active} defaults to true on create and keeps the
     * current value on update when omitted.
     */
    public record SubscriptionPlanRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,
            @Size(max = 255, message = "tagline must be at most 255 characters") String tagline,
            @Positive(message = "monthlyPrice must be positive; omit it if the plan is not sold monthly")
            Long monthlyPrice,
            @Positive(message = "yearlyPrice must be positive; omit it if the plan is not sold yearly")
            Long yearlyPrice,
            @NotNull(message = "maxBranches is required")
            @Min(value = 1, message = "maxBranches must be at least 1") Integer maxBranches,
            @NotNull(message = "maxCourts is required")
            @Min(value = 1, message = "maxCourts must be at least 1") Integer maxCourts,
            @Size(max = 30, message = "features accepts at most 30 items")
            List<@Size(max = 255, message = "each feature must be at most 255 characters") String> features,
            @Size(max = 20, message = "color must be at most 20 characters") String color,
            Boolean popular,
            Boolean active) {
    }

    public record SubscriptionPlanResponse(long id, String name, String tagline, Long monthlyPrice,
                                           Long yearlyPrice, int maxCourts, int maxBranches, boolean active,
                                           String color, boolean popular, List<String> features,
                                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
