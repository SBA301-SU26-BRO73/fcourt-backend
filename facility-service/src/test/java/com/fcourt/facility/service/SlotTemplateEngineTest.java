package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SlotTemplateEngineTest {

    @Test
    void splitsOpeningHoursIntoExactContiguousThirtyMinuteBlocks() {
        var blocks = SlotTemplateEngine.split(LocalTime.of(6, 0), LocalTime.of(22, 0), 30);

        assertThat(blocks).hasSize(32);
        assertThat(blocks.get(0)).isEqualTo(new SlotTemplateEngine.Block(LocalTime.of(6, 0), LocalTime.of(6, 30)));
        assertThat(blocks.get(1)).isEqualTo(new SlotTemplateEngine.Block(LocalTime.of(6, 30), LocalTime.of(7, 0)));
        assertThat(blocks.get(31).end()).isEqualTo(LocalTime.of(22, 0));
        for (int i = 1; i < blocks.size(); i++) {
            assertThat(blocks.get(i).start()).isEqualTo(blocks.get(i - 1).end());
        }
    }

    @Test
    void rejectsRangesThatCannotBeSplitWithoutFragments() {
        assertThatThrownBy(() -> SlotTemplateEngine.split(LocalTime.of(6, 0), LocalTime.of(7, 15), 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not divisible");
        assertThatThrownBy(() -> SlotTemplateEngine.split(LocalTime.of(8, 0), LocalTime.of(8, 0), 30))
                .hasMessageContaining("after startTime");
        assertThatThrownBy(() -> SlotTemplateEngine.split(LocalTime.of(9, 0), LocalTime.of(8, 0), 30))
                .hasMessageContaining("after startTime");
        assertThatThrownBy(() -> SlotTemplateEngine.split(LocalTime.of(6, 0), LocalTime.of(8, 0), 60))
                .hasMessageContaining("durationMinutes must be 30");
        assertThatThrownBy(() -> SlotTemplateEngine.split(LocalTime.of(6, 0, 10), LocalTime.of(8, 0), 30))
                .hasMessageContaining("minute precision");
    }

    @Test
    void derivesSlotPriceFromHourlyPriceAndMultiplier() {
        assertThat(SlotTemplateEngine.slotPrice(null, new BigDecimal("100000"), 30, null))
                .isEqualByComparingTo("50000");
        assertThat(SlotTemplateEngine.slotPrice(null, new BigDecimal("100000"), 30, new BigDecimal("1.5")))
                .isEqualByComparingTo("75000");
        assertThat(SlotTemplateEngine.slotPrice(new BigDecimal("42000"), new BigDecimal("100000"), 30, null))
                .isEqualByComparingTo("42000");
        assertThatThrownBy(() -> SlotTemplateEngine.slotPrice(null, null, 30, null))
                .hasMessageContaining("price is required");
    }
}
