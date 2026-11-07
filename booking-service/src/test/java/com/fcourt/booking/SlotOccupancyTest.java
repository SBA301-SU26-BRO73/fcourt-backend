package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SlotOccupancyTest {
  @Test
  void shiftedTemplatesOverlapButAdjacentSlotsDoNot() {
    var interval = new SlotOccupancy.Interval(LocalTime.of(10, 0), LocalTime.of(10, 30));
    assertThat(interval.overlaps(LocalTime.of(10, 15), LocalTime.of(10, 45))).isTrue();
    assertThat(interval.overlaps(LocalTime.of(10, 30), LocalTime.of(11, 0))).isFalse();
    assertThat(interval.overlaps(LocalTime.of(9, 30), LocalTime.of(10, 0))).isFalse();
  }

  @Test
  void midnightIsTheFollowingDayEnd() {
    var interval = new SlotOccupancy.Interval(LocalTime.of(23, 30), LocalTime.MIDNIGHT);
    assertThat(interval.overlaps(LocalTime.of(23, 45), LocalTime.MIDNIGHT)).isTrue();
    assertThat(interval.overlaps(LocalTime.of(23, 0), LocalTime.of(23, 30))).isFalse();
  }

  @Test
  void subsecondStoredBoundariesAreNotTruncated() {
    var interval =
        new SlotOccupancy.Interval(LocalTime.of(10, 0), LocalTime.of(10, 30, 0, 500000000));
    assertThat(interval.overlaps(LocalTime.of(10, 30, 0, 250000000), LocalTime.of(11, 0))).isTrue();
    assertThat(interval.overlaps(LocalTime.of(10, 30, 0, 500000000), LocalTime.of(11, 0)))
        .isFalse();
  }
}
