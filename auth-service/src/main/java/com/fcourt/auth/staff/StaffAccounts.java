package com.fcourt.auth.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.auth.account.AccountDtos;
import com.fcourt.auth.account.SessionService;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
class StaffAccounts {
    private final JdbcTemplate jdbc;
    private final ManagedStaffBoundary boundary;
    private final ObjectMapper json;
    private final SessionService sessions;
    private final TransactionTemplate transaction;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    StaffAccounts(
            JdbcTemplate jdbc,
            ManagedStaffBoundary boundary,
            ObjectMapper json,
            SessionService sessions,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.boundary = boundary;
        this.json = json;
        this.sessions = sessions;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(15);
    }

    StaffReactivation.Account create(Jwt jwt, String key, Create request) {
        admin(jwt);
        boundary.requireBranch(request.branchId(), jwt.getTokenValue());
        return transaction.execute(status -> createOnce(jwt, key, request));
    }

    private StaffReactivation.Account createOnce(Jwt jwt, String key, Create request) {
        long actor = admin(jwt);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String fingerprint =
                hash(
                        List.of(
                                request.branchId(),
                                email,
                                request.fullName().strip(),
                                request.phone()));
        UUID command = command(actor, key);
        Long replay = replay(actor, command, "CREATE", fingerprint);
        if (replay != null) return account(replay);
        byte[] encoded = request.password().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 72
                || !request.password().matches("(?s).*[A-Za-z].*")
                || !request.password().matches("(?s).*[0-9].*")) throw bad();
        // Serialise competing unique contacts before any insert; retain DB constraints too.
        var contacts = new ArrayList<>(List.of("email:" + email, "phone:" + request.phone()));
        Collections.sort(contacts);
        for (String contact : contacts)
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", contact);
        if (!jdbc.queryForList(
                        "SELECT id FROM users WHERE lower(email)=? OR (phone=? AND deleted_at IS"
                            + " NULL)",
                        email,
                        request.phone())
                .isEmpty()) throw conflict();
        long user;
        try {
            user =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "INSERT INTO users(email,password_hash,full_name,phone,role,status)"
                                        + " VALUES (?,?,?,?,'STAFF','ACTIVE') RETURNING id",
                                    Long.class,
                                    email,
                                    passwords.encode(request.password()),
                                    request.fullName().strip(),
                                    request.phone()));
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
        record(actor, command, "CREATE", fingerprint, user);
        audit(actor, user, "STAFF_CREATE", null, "ACTIVE");
        return account(user);
    }

    StaffReactivation.Account update(Jwt jwt, long user, String key, Update request) {
        admin(jwt);
        boundary.require(user, request.branchId(), jwt.getTokenValue());
        return transaction.execute(status -> updateOnce(jwt, user, key, request));
    }

    private StaffReactivation.Account updateOnce(Jwt jwt, long user, String key, Update request) {
        long actor = admin(jwt);
        UUID command = command(actor, key);
        String fingerprint =
                hash(
                        List.of(
                                user,
                                request.branchId(),
                                request.fullName().strip(),
                                request.phone()));
        Long replay = replay(actor, command, "UPDATE", fingerprint);
        if (replay != null) return account(replay);
        requireActive(user);
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "phone:" + request.phone());
        if (!jdbc.queryForList(
                        "SELECT id FROM users WHERE phone=? AND deleted_at IS NULL AND id<>?",
                        request.phone(),
                        user)
                .isEmpty()) throw conflict();
        jdbc.update(
                "UPDATE users SET full_name=?,phone=?,updated_at=now() WHERE id=?",
                request.fullName().strip(),
                request.phone(),
                user);
        record(actor, command, "UPDATE", fingerprint, user);
        audit(actor, user, "STAFF_UPDATE", "ACTIVE", "ACTIVE");
        return account(user);
    }

    StaffReactivation.Account disable(Jwt jwt, long user, String key) {
        admin(jwt);
        boundary.require(user, 0, jwt.getTokenValue());
        return transaction.execute(status -> disableOnce(jwt, user, key));
    }

    private StaffReactivation.Account disableOnce(Jwt jwt, long user, String key) {
        long actor = admin(jwt);
        UUID command = command(actor, key);
        String fingerprint = hash(List.of(user));
        Long replay = replay(actor, command, "DISABLE", fingerprint);
        if (replay != null) return account(replay);
        var target = lock(user);
        if (!"STAFF".equals(target.role())
                || !Set.of("ACTIVE", "INACTIVE").contains(target.status())) throw conflict();
        if ("ACTIVE".equals(target.status())) {
            jdbc.update("UPDATE users SET status='INACTIVE',updated_at=now() WHERE id=?", user);
            audit(actor, user, "STAFF_DISABLE", "ACTIVE", "INACTIVE");
        }
        sessions.closeAll(user);
        record(actor, command, "DISABLE", fingerprint, user);
        return account(user);
    }

    private long admin(Jwt jwt) {
        long actor;
        try {
            actor = Long.parseLong(jwt.getSubject());
        } catch (Exception e) {
            throw forbidden();
        }
        var user = account(actor);
        if (!"ADMIN".equals(user.role()) || !"ACTIVE".equals(user.status())) throw forbidden();
        return actor;
    }

    private void requireActive(long user) {
        var target = lock(user);
        if (!"STAFF".equals(target.role()) || !"ACTIVE".equals(target.status())) throw conflict();
    }

    private StaffReactivation.Account lock(long id) {
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", id);
        return account(id);
    }

    private StaffReactivation.Account account(long id) {
        var rows =
                jdbc.query(
                        "SELECT id,email,full_name,phone,role,status FROM users WHERE id=? AND"
                            + " deleted_at IS NULL",
                        (r, n) ->
                                new StaffReactivation.Account(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getString(6)),
                        id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        return rows.getFirst();
    }

    private UUID command(long actor, String key) {
        try {
            if (!key.startsWith("staff:" + actor + ":")) throw new IllegalArgumentException();
            UUID value = UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
            jdbc.queryForList(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", actor + ":" + value);
            return value;
        } catch (IllegalArgumentException e) {
            throw bad();
        }
    }

    private Long replay(long actor, UUID key, String kind, String fingerprint) {
        var rows =
                jdbc.queryForList(
                        "SELECT kind,fingerprint,user_id FROM staff_account_commands WHERE"
                            + " actor_id=? AND command_key=?",
                        actor,
                        key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!kind.equals(row.get("kind")) || !fingerprint.equals(row.get("fingerprint")))
            throw conflict();
        return ((Number) row.get("user_id")).longValue();
    }

    private void record(long actor, UUID key, String kind, String fingerprint, long user) {
        jdbc.update(
                "INSERT INTO staff_account_commands(actor_id,command_key,kind,fingerprint,user_id)"
                    + " VALUES (?,?,?,?,?)",
                actor,
                key,
                kind,
                fingerprint,
                user);
    }

    private void audit(long actor, long user, String action, String previous, String next) {
        jdbc.update(
                "INSERT INTO"
                    + " user_account_action_logs(target_user_id,performed_by_id,action,previous_status,new_status,reason)"
                    + " VALUES (?,?,?,?,?,'Managed branch staff command')",
                user,
                actor,
                action,
                previous,
                next);
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(value)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode command");
        }
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid staff account command");
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Active administrator required");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Staff account conflict");
    }

    record Create(
            @jakarta.validation.constraints.Positive long branchId,
            @jakarta.validation.constraints.Email
                    @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(max = 255)
                    String email,
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(min = 12, max = 72)
                    String password,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 150)
                    String fullName,
            @jakarta.validation.constraints.NotNull
                    @jakarta.validation.constraints.Pattern(regexp = AccountDtos.PHONE)
                    String phone) {
        @Override
        public String toString() {
            return "Create[REDACTED]";
        }
    }

    record Update(
            @jakarta.validation.constraints.Positive long branchId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 150)
                    String fullName,
            @jakarta.validation.constraints.NotNull
                    @jakarta.validation.constraints.Pattern(regexp = AccountDtos.PHONE)
                    String phone) {}
}
