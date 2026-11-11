package com.fcourt.booking.service;

import com.fcourt.booking.dto.request.GuestLookupRequest;
import com.fcourt.booking.dto.response.GuestBookingResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Tra cứu booking của khách vãng lai (không tài khoản) bằng mã booking + số điện thoại. */
@Service
public class GuestBookingLookupService {
    private static final Logger log = LoggerFactory.getLogger(GuestBookingLookupService.class);
    private static final String NOT_FOUND = "Booking not found";
    /** Số giả để thời gian so sánh không khác nhau giữa "không có mã" và "sai số". */
    private static final String DUMMY_PHONE = "0000000000";

    private final JdbcTemplate jdbc;

    public GuestBookingLookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public GuestBookingResponse lookup(GuestLookupRequest request) {
        String code = request.bookingCode().strip().toUpperCase(Locale.ROOT).replace("-", "");
        var rows = jdbc.queryForList("SELECT * FROM bookings WHERE booking_code=? AND customer_id IS NULL", code);
        String stored = rows.isEmpty() ? null : (String) rows.getFirst().get("guest_phone");
        boolean phoneMatches = phoneEquals(stored == null ? DUMMY_PHONE : stored, request.phone());
        if (rows.isEmpty() || stored == null || !phoneMatches) {
            log.info("guest lookup miss codeHash={}", shortHash(code));
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        var row = rows.getFirst();
        long id = ((Number) row.get("id")).longValue();
        var slots = jdbc.query(
                "SELECT slot_start,slot_end,price_at_booking FROM booking_slots WHERE booking_id=? ORDER BY slot_start,id",
                (rs, i) -> new GuestBookingResponse.Slot(
                        rs.getObject(1, LocalTime.class), rs.getObject(2, LocalTime.class), rs.getBigDecimal(3)),
                id);
        String status = (String) row.get("status");
        boolean confirmed = "CONFIRMED".equals(status);
        return new GuestBookingResponse(
                id, code, (String) row.get("branch_name"), (String) row.get("branch_address"),
                (String) row.get("court_name"), ((java.sql.Date) row.get("date")).toLocalDate(),
                List.copyOf(slots), (java.math.BigDecimal) row.get("total_price"), status,
                (String) row.get("payment_status"),
                confirmed ? (String) row.get("checkin_code") : null,
                confirmed ? (String) row.get("qr_image_url") : null);
    }

    /** 0901234567, 84901234567 và +84901234567 coi là cùng một số. */
    static String canonicalPhone(String phone) {
        String digits = phone.strip().replace("+", "");
        return digits.startsWith("84") && digits.length() == 11 ? "0" + digits.substring(2) : digits;
    }

    public static boolean phoneEquals(String stored, String given) {
        return MessageDigest.isEqual(
                canonicalPhone(stored).getBytes(StandardCharsets.US_ASCII),
                canonicalPhone(given).getBytes(StandardCharsets.US_ASCII));
    }

    private static String shortHash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
