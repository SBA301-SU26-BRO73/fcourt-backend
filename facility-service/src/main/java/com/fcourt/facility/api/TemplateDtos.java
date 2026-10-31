package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Request/response shapes for 30-minute time slot templates (SCRUM-18). */
public final class TemplateDtos {
    private TemplateDtos() { }

    public record ApplyTemplateRequest(
            LocalTime startTime,
            LocalTime endTime,
            Integer durationMinutes,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
            @Digits(integer = 4, fraction = 4) BigDecimal priceMultiplier,
            List<@NotNull @Min(0) @Max(6) Integer> daysOfWeek) {

        @JsonIgnore
        @AssertTrue(message = "daysOfWeek must not be empty or contain duplicates")
        public boolean isDaysValid() {
            return daysOfWeek == null
                    || (!daysOfWeek.isEmpty() && daysOfWeek.stream().distinct().count() == daysOfWeek.size());
        }
    }

    public record SingleTemplateRequest(
            @NotNull @Min(0) @Max(6) Integer dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price) { }

    public record PreviewSlot(@JsonFormat(pattern = "HH:mm") LocalTime startTime,
                              @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                              BigDecimal price) { }

    public record PreviewResponse(long courtId, List<PreviewSlot> slots, int totalSlots) { }

    public record TemplateSlot(long id, long courtId, int dayOfWeek,
                               @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                               @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                               BigDecimal price, boolean active) { }

    public record ApplyResponse(long courtId, List<Integer> daysOfWeek, int totalSlots, List<TemplateSlot> slots) { }

    /** Contract consumed by discovery-service (SCRUM-22_AVAILABLE_SLOTS.md). */
    public record SlotSchedule(long courtId, LocalDate date, boolean outOfService, List<ScheduleSlot> slots) { }

    public record ScheduleSlot(long slotId,
                               @JsonFormat(pattern = "HH:mm:ss") LocalTime startTime,
                               @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime,
                               BigDecimal price, boolean outOfService) { }
}
