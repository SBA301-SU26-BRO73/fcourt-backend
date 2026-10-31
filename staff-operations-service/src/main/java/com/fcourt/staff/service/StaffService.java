package com.fcourt.staff.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.staff.api.StaffRequests.Create;
import com.fcourt.staff.api.StaffRequests.Shift;
import com.fcourt.staff.api.StaffRequests.Update;
import com.fcourt.staff.api.StaffView;
import com.fcourt.staff.integration.StaffUpstreams;
import com.fcourt.staff.persistence.StaffStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaffService {
    private final StaffStore store;
    private final StaffUpstreams upstream;
    private final ObjectMapper json;

    public StaffService(StaffStore store, StaffUpstreams upstream, ObjectMapper json) {
        this.store = store;
        this.upstream = upstream;
        this.json = json;
    }

    public List<StaffView> list(Jwt jwt, long branch, int limit, int offset) {
        admin(jwt);
        upstream.ownedBranch(branch, actor(jwt), jwt.getTokenValue());
        return store.list(branch, limit, offset);
    }

    public StaffView assigned(Jwt jwt) {
        upstream.activeUser(actor(jwt), "STAFF", jwt.getTokenValue());
        StaffView assigned = store.forUser(actor(jwt));
        upstream.activeBranch(assigned.branchId(), jwt.getTokenValue());
        return assigned;
    }

    @Transactional(timeout = 15)
    public StaffView create(Jwt jwt, UUID key, Create request) {
        admin(jwt);
        upstream.ownedBranch(request.branchId(), actor(jwt), jwt.getTokenValue());
        validateShifts(request.shifts());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds encoded length limit");
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        // Credentials never enter the idempotency/audit database or command fingerprint.
        String fingerprint = fingerprint(List.of(request.branchId(), email, request.fullName().strip(),
                request.phone(), request.shifts()));
        store.lockCommand(actor(jwt), key);
        Long replay = store.replay(actor(jwt), key, "CREATE", fingerprint);
        if (replay != null) return store.get(replay, false);
        var account = upstream.create(new StaffUpstreams.CreateAccount(request.branchId(), email,
                request.password(), request.fullName().strip(), request.phone()), upstreamKey(jwt, key), jwt.getTokenValue());
        long id = store.insert(account, request.branchId());
        store.replaceShifts(id, request.shifts());
        store.record(actor(jwt), key, "CREATE", fingerprint, id);
        return store.get(id, false);
    }

    @Transactional(timeout = 15)
    public StaffView update(Jwt jwt, long id, UUID key, Update request) {
        admin(jwt);
        validateShifts(request.shifts());
        store.lockCommand(actor(jwt), key);
        StaffView staff = owned(jwt, id);
        String fingerprint = fingerprint(List.of(id, request.fullName().strip(), request.phone(), request.shifts()));
        Long replay = store.replay(actor(jwt), key, "UPDATE", fingerprint);
        if (replay != null) return store.get(replay, false);
        if (!staff.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Staff assignment is inactive");
        var account = upstream.update(staff.userId(), new StaffUpstreams.UpdateAccount(staff.branchId(),
                request.fullName().strip(), request.phone()), upstreamKey(jwt, key), jwt.getTokenValue());
        store.update(id, account);
        store.replaceShifts(id, request.shifts());
        store.record(actor(jwt), key, "UPDATE", fingerprint, id);
        return store.get(id, false);
    }

    @Transactional(timeout = 15)
    public StaffView disable(Jwt jwt, long id, UUID key) {
        admin(jwt);
        store.lockCommand(actor(jwt), key);
        StaffView staff = owned(jwt, id);
        String fingerprint = fingerprint(List.of(id));
        Long replay = store.replay(actor(jwt), key, "DISABLE", fingerprint);
        if (replay != null) return store.get(replay, false);
        if (staff.active()) {
            upstream.disable(staff.userId(), upstreamKey(jwt, key), jwt.getTokenValue());
            store.disable(id);
        }
        store.record(actor(jwt), key, "DISABLE", fingerprint, id);
        return store.get(id, false);
    }

    private StaffView owned(Jwt jwt, long id) {
        StaffView staff = store.get(id, true);
        upstream.ownedBranch(staff.branchId(), actor(jwt), jwt.getTokenValue());
        return staff;
    }

    private void admin(Jwt jwt) { upstream.activeUser(actor(jwt), "ADMIN", jwt.getTokenValue()); }
    private long actor(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
    private String upstreamKey(Jwt jwt, UUID key) { return "staff:" + actor(jwt) + ":" + key; }

    private String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot encode command", error);
        }
    }

    public static void validateShifts(List<Shift> shifts) {
        List<Shift> sorted = shifts.stream().sorted(Comparator.comparingInt(Shift::dayOfWeek)
                .thenComparing(Shift::startTime)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            Shift shift = sorted.get(i);
            if (!shift.endTime().isAfter(shift.startTime()) || shift.startTime().getSecond() != 0
                    || shift.endTime().getSecond() != 0 || shift.startTime().getNano() != 0 || shift.endTime().getNano() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shift must be within one day at minute precision");
            }
            if (i > 0 && sorted.get(i - 1).dayOfWeek() == shift.dayOfWeek()
                    && shift.startTime().isBefore(sorted.get(i - 1).endTime())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Work shifts overlap");
            }
        }
    }
}
