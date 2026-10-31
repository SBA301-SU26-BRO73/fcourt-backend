package com.fcourt.facility.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an opening range into back-to-back blocks of a fixed length (SCRUM-18).
 * The result covers the range exactly: no gaps, no overlap, no partial trailing block.
 */
public final class SlotTemplateEngine {
    public static final int SLOT_MINUTES = 30;

    public record Block(LocalTime start, LocalTime end) { }

    private SlotTemplateEngine() { }

    /**
     * @throws IllegalArgumentException with a user-safe message when the range cannot be split exactly
     */
    public static List<Block> split(LocalTime start, LocalTime end, int durationMinutes) {
        if (durationMinutes != SLOT_MINUTES) {
            throw new IllegalArgumentException("durationMinutes must be " + SLOT_MINUTES);
        }
        if (start == null || end == null) throw new IllegalArgumentException("startTime and endTime are required");
        if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
            throw new IllegalArgumentException("startTime and endTime must have minute precision");
        }
        if (!end.isAfter(start)) throw new IllegalArgumentException("endTime must be after startTime");
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes % durationMinutes != 0) {
            throw new IllegalArgumentException("Range " + start + "-" + end + " is not divisible into "
                    + durationMinutes + "-minute slots");
        }
        List<Block> blocks = new ArrayList<>((int) (minutes / durationMinutes));
        for (LocalTime cursor = start; cursor.isBefore(end); cursor = cursor.plusMinutes(durationMinutes)) {
            blocks.add(new Block(cursor, cursor.plusMinutes(durationMinutes)));
        }
        return blocks;
    }

    /** price ?? defaultPricePerHour * durationMinutes / 60 * multiplier, rounded to 2 decimals (VND). */
    public static BigDecimal slotPrice(BigDecimal explicitPrice, BigDecimal defaultPricePerHour,
                                       int durationMinutes, BigDecimal multiplier) {
        if (explicitPrice != null) return explicitPrice.setScale(2, RoundingMode.HALF_UP);
        if (defaultPricePerHour == null) {
            throw new IllegalArgumentException("price is required because the court has no defaultPricePerHour");
        }
        BigDecimal factor = multiplier == null ? BigDecimal.ONE : multiplier;
        return defaultPricePerHour.multiply(BigDecimal.valueOf(durationMinutes)).multiply(factor)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
