package com.fcourt.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chủ chi nhánh hủy một booking thường (ví dụ sân hỏng). Dùng lại BookingCancellation.applyCancellation nên
 * trạng thái, slot, outbox BookingCancelledEvent giống hệt khách tự hủy; khác ở chỗ bỏ ràng buộc chủ booking,
 * bỏ hạn hủy, và hoàn 100% nếu đã thanh toán (sân là bên hủy).
 */
@Service
class AdminBookingCancellation {
    private static final Set<String> CANCELLABLE = Set.of("PENDING_PAYMENT", "AWAITING_CONFIRMATION", "CONFIRMED");

    private final JdbcTemplate jdbc;
    private final BookingCancellation cancellation;
    private final BookingQueries queries;
    private final AdminBranchAuthority authority;
    private final TransactionTemplate tx;

    AdminBookingCancellation(JdbcTemplate jdbc, BookingCancellation cancellation, BookingQueries queries,
            AdminBranchAuthority authority, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.cancellation = cancellation;
        this.queries = queries;
        this.authority = authority;
        this.tx = tx;
    }

    /** Gọi facility để lấy chi nhánh của owner nằm ngoài transaction DB (HTTP), rồi mới khóa dòng booking. */
    BookingView cancel(long id, String reason, Jwt jwt) {
        long actor = BookingQueries.actor(jwt);
        List<Long> branches = authority.ownedBranches(jwt);
        return tx.execute(status -> cancelOwned(id, reason.strip(), actor, branches));
    }


    private BookingView cancelOwned(long id, String reason, long actor, List<Long> branches) {
        var rows = jdbc.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty() || !(rows.getFirst().get("branch_id") instanceof Number branch)
                || !branches.contains(branch.longValue()))
            throw BookingQueries.missing();
        Map<String, Object> booking = rows.getFirst();
        String status = booking.get("status").toString();
        if ("CANCELLED".equals(status)) return queries.view(id);
        if (!CANCELLABLE.contains(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking in status " + status + " cannot be cancelled");
        if (!jdbc.queryForList("SELECT 1 FROM full_court_package_bookings WHERE booking_id=?", id).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking belongs to a full-court package; cancel the package instead");
        BigDecimal refund = "CONFIRMED".equals(booking.get("payment_status"))
                ? (BigDecimal) booking.get("total_price") : BigDecimal.ZERO;
        var policy = new BookingCancellation.Policy(true, "Hủy bởi chủ sân", (Instant) null, (Instant) null, 0,
                refund, BigDecimal.valueOf(100), "VND");
        cancellation.applyCancellation(booking, reason, policy, "ADMIN");
        jdbc.update("UPDATE bookings SET cancelled_by=?,cancelled_by_role='ADMIN' WHERE id=?", actor, id);
        return queries.view(id);
    }
}
