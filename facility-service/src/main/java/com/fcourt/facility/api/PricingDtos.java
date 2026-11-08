package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fcourt.facility.service.PricingEngine.DayType;
import com.fcourt.facility.service.PricingEngine.Source;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Request/response shapes of court pricing rules (SCRUM-80, SCRUM-82). Day numbers: 0 = Sunday .. 6 = Saturday. */
public final class PricingDtos {
    private PricingDtos() { }

    public record RuleRequest(
            @NotBlank @Size(max = 100) String ruleName,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
            @Digits(integer = 8, fraction = 2) BigDecimal pricePerHour,
            Boolean peakHour,
            List<@NotNull @Min(0) @Max(6) Integer> daysOfWeek) {

        @JsonIgnore
        @AssertTrue(message = "daysOfWeek must not be empty or contain duplicates")
        public boolean isDaysValid() {
            return daysOfWeek == null
                    || (!daysOfWeek.isEmpty() && daysOfWeek.stream().distinct().count() == daysOfWeek.size());
        }
    }

    public record RuleResponse(long id, long courtId, String ruleName,
                               @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                               @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                               BigDecimal pricePerHour, boolean peakHour, List<Integer> daysOfWeek,
                               DayType dayType) { }

    public record CopyRequest(
            @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
            @Digits(integer = 4, fraction = 4) BigDecimal priceMultiplier) { }

    public record QuoteSlot(@JsonFormat(pattern = "HH:mm") LocalTime startTime,
                            @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                            BigDecimal price, Source source, Long ruleId) { }

    /** Price breakdown of a booking range; a range crossing a rule boundary mixes sources slot by slot. */
    public record Quote(long courtId, LocalDate date, BigDecimal total, List<QuoteSlot> slots) { }
}
