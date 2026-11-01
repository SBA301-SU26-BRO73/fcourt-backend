package com.fcourt.subscription.courttype;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Request/response shapes of the court type API (see README, subscription-service). */
public final class CourtTypeDtos {
    private CourtTypeDtos() {
    }

    /**
     * Full replacement on PUT. {@code active} defaults to true on create and keeps the current value on
     * update when omitted, so a client that does not send it cannot re-enable a disabled type by accident.
     */
    public record CourtTypeRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,
            @Size(max = 100, message = "nameEn must be at most 100 characters") String nameEn,
            @Size(max = 2000, message = "description must be at most 2000 characters") String description,
            @Size(max = 50, message = "icon must be at most 50 characters") String icon,
            @Size(max = 20, message = "color must be at most 20 characters") String color,
            Boolean active) {
    }

    public record CourtTypeResponse(long id, String name, String nameEn, String description, String icon,
                                    String color, boolean active, OffsetDateTime createdAt,
                                    OffsetDateTime updatedAt) {
    }
}
