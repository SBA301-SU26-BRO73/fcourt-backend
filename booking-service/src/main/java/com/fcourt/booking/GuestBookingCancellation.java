package com.fcourt.booking;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/** Public facade so the controller package can reach the package-private cancellation policy. */
@Service
public class GuestBookingCancellation {
    public record Result(String bookingCode, String status, BigDecimal refundAmount, String currency) {}

    private final BookingCancellation cancellation;

    GuestBookingCancellation(BookingCancellation cancellation) {
        this.cancellation = cancellation;
    }

    public Result cancel(String bookingCode, String phone, String reason) {
        var r = cancellation.cancelAsGuest(bookingCode, phone, reason);
        return new Result(r.bookingCode(), r.status(), r.refundAmount(), r.currency());
    }
}
