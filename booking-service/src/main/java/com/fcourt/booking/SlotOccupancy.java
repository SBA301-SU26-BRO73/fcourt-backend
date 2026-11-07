package com.fcourt.booking;

import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compare time intervals, because facility may retire/re-time template IDs. */
final class SlotOccupancy {
  private SlotOccupancy() {}

  static List<Interval> active(JdbcTemplate jdbc, long court, LocalDate date) {
    return jdbc.query(
        "SELECT slot_start,slot_end FROM booking_slots WHERE court_id=? AND booking_date=? AND"
            + " active",
        (r, n) -> new Interval(r.getObject(1, LocalTime.class), r.getObject(2, LocalTime.class)),
        court,
        date);
  }

  record Interval(LocalTime start, LocalTime end) {
    boolean overlaps(LocalTime from, LocalTime until) {
      long midnight = Duration.ofDays(1).toNanos();
      long finish = end.equals(LocalTime.MIDNIGHT) ? midnight : end.toNanoOfDay();
      long proposed = until.equals(LocalTime.MIDNIGHT) ? midnight : until.toNanoOfDay();
      return start.toNanoOfDay() < proposed && from.toNanoOfDay() < finish;
    }
  }
}
