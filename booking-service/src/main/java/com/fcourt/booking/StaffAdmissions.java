package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.fcourt.booking.exception.CheckInRejectedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
class StaffAdmissions {
    private final JdbcTemplate jdbc;
    private final StaffAuthority authority;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] credential;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    StaffAdmissions(
            JdbcTemplate jdbc,
            StaffAuthority authority,
            ObjectMapper json,
            Clock clock,
            @Value("${STAFF_BOOKING_SERVICE_TOKEN:}") String credential) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.json = json;
        this.clock = clock;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
    }

    Ticket preview(Jwt jwt, long branch, long actor, String code, LocalDate date) {
        authority.require(jwt, branch, actor);
        if (!LocalDate.now(clock.withZone(ZONE)).equals(date)) throw conflict();
        var tickets = tickets(branch, date, code);
        if (tickets.isEmpty()) throw missing();
        var ticket = tickets.getFirst();
        var from =
                ticket.date().atTime(ticket.slotStart()).atZone(ZONE).toInstant().minusSeconds(900);
        var until =
                ticket.date()
                        .plusDays(ticket.slotEnd().equals(LocalTime.MIDNIGHT) ? 1 : 0)
                        .atTime(ticket.slotEnd())
                        .atZone(ZONE)
                        .toInstant();
        if (clock.instant().isBefore(from) || !clock.instant().isBefore(until)) throw conflict();
        return ticket;
    }

    Roster roster(Jwt jwt, long branch, LocalDate date) {
        authority.require(jwt, branch, BookingQueries.actor(jwt));
        if (!LocalDate.now(clock.withZone(ZONE)).equals(date)) throw conflict();
        return new Roster(branch, tickets(branch, date, null));
    }

    private List<Ticket> tickets(long branch, LocalDate date, String code) {
        // One bounded query. Gapped/overlapping slot sets cannot mint a broad offline permit.
        String sql =
                """
WITH ordered AS (
  SELECT s.*,lag(slot_end) OVER(PARTITION BY booking_id ORDER BY slot_start) AS previous_end
  FROM booking_slots s JOIN bookings b ON b.id=s.booking_id WHERE b.branch_id=? AND b.date=?
), spans AS (
  SELECT booking_id,min(slot_start) AS first_start,
    CASE WHEN bool_or(slot_end='00:00'::time) THEN '00:00'::time ELSE max(slot_end) END AS last_end,
    bool_and(previous_end IS NULL OR previous_end=slot_start) AS contiguous
  FROM ordered GROUP BY booking_id
)
SELECT b.*,s.first_start,s.last_end FROM bookings b JOIN spans s ON s.booking_id=b.id
  WHERE b.branch_id=? AND b.date=? AND b.status='CONFIRMED' AND b.checkin_code IS NOT NULL
  AND s.contiguous
"""
                        + (code == null ? "" : " AND (b.checkin_code=? OR b.booking_code=?)")
                        + " ORDER BY b.id LIMIT 5001";
        var args = new ArrayList<Object>(List.of(branch, date, branch, date));
        if (code != null) {
            args.add(code.strip());
            args.add(manualCode(code));
        }
        var rows =
                jdbc.query(
                        sql,
                        (r, n) ->
                                new Ticket(
                                        r.getLong("id"),
                                        r.getLong("branch_id"),
                                        r.getLong("court_id"),
                                        Objects.requireNonNullElse(
                                                r.getString("court_name"),
                                                "Court " + r.getLong("court_id")),
                                        r.getObject("date", LocalDate.class),
                                        r.getObject("first_start", LocalTime.class),
                                        r.getObject("last_end", LocalTime.class),
                                        r.getString("status"),
                                        r.getString("customer_name"),
                                        r.getString("guest_phone"),
                                        r.getString("checkin_code")),
                        args.toArray());
        if (rows.size() > 5000)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Ticket roster exceeds limit");
        return rows;
    }

    @Transactional(timeout = 15)
    Result online(Jwt jwt, Online command, String key) {
        authority.require(jwt, command.branchId(), command.actorId());
        String code = command.checkInCode();
        if (command.bookingId() != null) {
            // One-tap check-in from the schedule: the staff app only knows the booking id, never the ticket code.
            if (command.bookingId() <= 0 || !"MANUAL".equals(command.channel())) throw bad();
            code = ticketCodeOf(command.bookingId(), command.branchId());
        }
        if (code == null
                || code.isBlank()
                || code.length() > 128
                || !Set.of("QR", "MANUAL")
                        .contains(Objects.requireNonNullElse(command.channel(), ""))) throw bad();
        String normalized = code.strip();
        if (!key.startsWith("staff:check-in:" + command.actorId() + ":")) throw bad();
        try {
            UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
        } catch (Exception e) {
            throw bad();
        }
        String fingerprint = hash(List.of(command.branchId(), normalized, command.channel()));
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                command.actorId() + ":" + key);
        var replay =
                jdbc.queryForList(
                        "SELECT fingerprint,result::text AS result FROM booking_staff_commands"
                            + " WHERE actor_id=? AND command_key=?",
                        command.actorId(),
                        key);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(replay.getFirst().get("fingerprint"))) throw conflict();
            try {
                return json.readValue((String) replay.getFirst().get("result"), Result.class);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid command result");
            }
        }
        if (command.date() == null
                || command.checkedInAt() == null
                || !LocalDate.now(clock.withZone(ZONE)).equals(command.date())
                || command.checkedInAt().isBefore(clock.instant().minusSeconds(60))
                || command.checkedInAt().isAfter(clock.instant().plusSeconds(60))) throw bad();
        String match =
                "MANUAL".equals(command.channel())
                        ? "(checkin_code=? OR booking_code=?)"
                        : "checkin_code=?";
        var args = new ArrayList<Object>(List.of(command.branchId(), normalized));
        if ("MANUAL".equals(command.channel())) args.add(manualCode(normalized));
        var rows =
                jdbc.queryForList(
                        "SELECT id,status,date FROM bookings WHERE branch_id=? AND "
                                + match
                                + " FOR UPDATE",
                        args.toArray());
        if (rows.isEmpty()) {
            var otherBranch = jdbc.queryForList("SELECT id FROM bookings WHERE branch_id<>? AND (" + match + ")",
                    args.toArray());
            if (!otherBranch.isEmpty()) throw checkInRejected("WRONG_BRANCH");
            throw missing();
        }
        if (rows.size() != 1) throw missing();
        var row = rows.getFirst();
        long id = ((Number) row.get("id")).longValue();
        if ("CHECKED_IN".equals(row.get("status"))) throw checkInRejected("ALREADY_CHECKED_IN");
        if (!command.date().equals(((java.sql.Date) row.get("date")).toLocalDate()))
            throw checkInRejected("WRONG_DATE");
        if (!"CONFIRMED".equals(row.get("status"))) throw conflict();
        var ticket =
                tickets(command.branchId(), command.date(), normalized).stream()
                        .filter(t -> t.bookingId() == id)
                        .findFirst()
                        .orElseThrow(StaffAdmissions::missing);
        var from =
                ticket.date().atTime(ticket.slotStart()).atZone(ZONE).toInstant().minusSeconds(900);
        var until =
                ticket.date()
                        .plusDays(ticket.slotEnd().equals(LocalTime.MIDNIGHT) ? 1 : 0)
                        .atTime(ticket.slotEnd())
                        .atZone(ZONE)
                        .toInstant();
        if (command.checkedInAt().isBefore(from)) throw checkInRejected("CHECKIN_TOO_EARLY");
        if (!command.checkedInAt().isBefore(until)) throw conflict();
        jdbc.update(
                "UPDATE bookings SET status='CHECKED_IN',checked_in_at=?,updated_at=now() WHERE"
                    + " id=?",
                java.sql.Timestamp.from(command.checkedInAt()),
                id);
        var result =
                new Result(
                        id,
                        command.branchId(),
                        command.date(),
                        "CHECKED_IN",
                        command.checkedInAt());
        try {
            jdbc.update(
                    "INSERT INTO booking_staff_commands(actor_id,command_key,fingerprint,result)"
                        + " VALUES (?,?,?,?::jsonb)",
                    command.actorId(),
                    key,
                    fingerprint,
                    json.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist admission");
        }
        return result;
    }

    /** Ticket code of a booking, resolved server side. A booking of another branch looks like a missing one unless it has a code (then the usual WRONG_BRANCH rule applies). */
    private String ticketCodeOf(long bookingId, long branch) {
        var rows = jdbc.queryForList("SELECT checkin_code,branch_id FROM bookings WHERE id=?", bookingId);
        if (rows.isEmpty()) throw missing();
        var code = (String) rows.getFirst().get("checkin_code");
        if (code == null || code.isBlank()) {
            if (((Number) rows.getFirst().get("branch_id")).longValue() != branch) throw missing();
            throw conflict();
        }
        return code;
    }

    private static String manualCode(String code) {
        String normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("FC-[0-9]+") ? normalized.replace("-", "") : normalized;
    }

    @Transactional(timeout = 15)
    Result offline(Jwt jwt, Offline command, String key, String serviceToken) {
        if (credential.length < 32)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Offline authority is not configured");
        if (serviceToken == null
                || !MessageDigest.isEqual(
                        credential, serviceToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Staff service credential required");
        }
        authority.require(jwt, command.branchId(), command.actorId());
        if (!key.startsWith("staff:offline:" + command.actorId() + ":")) throw bad();
        try {
            UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
        } catch (Exception e) {
            throw bad();
        }
        String fingerprint = hash(command);
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                command.actorId() + ":" + key);
        var replay =
                jdbc.queryForList(
                        "SELECT fingerprint,result::text AS result FROM booking_staff_commands"
                            + " WHERE actor_id=? AND command_key=?",
                        command.actorId(),
                        key);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(replay.getFirst().get("fingerprint"))) throw conflict();
            try {
                return json.readValue((String) replay.getFirst().get("result"), Result.class);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid command result");
            }
        }
        if (command.checkedInAt() == null
                || command.date() == null
                || command.bookingId() <= 0
                || command.checkedInAt().isAfter(clock.instant().plusSeconds(60))
                || clock.instant()
                        .isAfter(command.date().plusDays(2).atStartOfDay(ZONE).toInstant()))
            throw bad();
        var rows =
                jdbc.queryForList(
                        "SELECT status,date FROM bookings WHERE id=? AND branch_id=? FOR UPDATE",
                        command.bookingId(),
                        command.branchId());
        if (rows.isEmpty()) throw missing();
        if (!"CONFIRMED".equals(rows.getFirst().get("status"))) throw conflict();
        var tickets = tickets(command.branchId(), command.date(), null);
        var ticket =
                tickets.stream()
                        .filter(t -> t.bookingId() == command.bookingId())
                        .findFirst()
                        .orElseThrow(StaffAdmissions::missing);
        var from =
                ticket.date().atTime(ticket.slotStart()).atZone(ZONE).toInstant().minusSeconds(900);
        var end =
                ticket.date()
                        .plusDays(ticket.slotEnd().equals(LocalTime.MIDNIGHT) ? 1 : 0)
                        .atTime(ticket.slotEnd())
                        .atZone(ZONE)
                        .toInstant();
        if (command.checkedInAt().isBefore(from) || !command.checkedInAt().isBefore(end))
            throw conflict();
        jdbc.update(
                "UPDATE bookings SET status='CHECKED_IN',checked_in_at=?,updated_at=now() WHERE"
                    + " id=?",
                java.sql.Timestamp.from(command.checkedInAt()),
                command.bookingId());
        var result =
                new Result(
                        command.bookingId(),
                        command.branchId(),
                        ticket.date(),
                        "CHECKED_IN",
                        command.checkedInAt());
        try {
            jdbc.update(
                    "INSERT INTO booking_staff_commands(actor_id,command_key,fingerprint,result)"
                        + " VALUES (?,?,?,?::jsonb)",
                    command.actorId(),
                    key,
                    fingerprint,
                    json.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist command");
        }
        return result;
    }

    private String hash(Object command) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(command)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash command");
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ticket date or state conflict");
    }

    private static CheckInRejectedException checkInRejected(String reason) {
        return new CheckInRejectedException(reason);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid staff admission command");
    }

    record Ticket(
            long bookingId,
            long branchId,
            long courtId,
            String courtName,
            LocalDate date,
            LocalTime slotStart,
            LocalTime slotEnd,
            String status,
            String customerName,
            String guestPhone,
            String checkInCode) {
        @Override
        public String toString() {
            return "Ticket[REDACTED]";
        }
    }

    record Roster(long branchId, List<Ticket> tickets) {}

    record Offline(
            long branchId, long actorId, long bookingId, LocalDate date, Instant checkedInAt) {}

    record Online(
            long branchId,
            long actorId,
            String checkInCode,
            LocalDate date,
            java.time.Instant checkedInAt,
            String channel,
            Long bookingId) {
        @Override
        public String toString() {
            return "Online[REDACTED]";
        }
    }

    record Result(
            long bookingId, long branchId, LocalDate date, String status, Instant checkedInAt) {}
}
