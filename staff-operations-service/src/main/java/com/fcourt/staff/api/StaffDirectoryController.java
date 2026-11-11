package com.fcourt.staff.api;

import com.fcourt.staff.persistence.StaffStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Active staff user ids of a branch, for notification-service (in-app notifications are addressed by user id).
 * Not a user API: callers present the shared {@code X-Internal-Service-Token}; with no token configured (or a short
 * one) every call answers 503, never open.
 */
@RestController
class StaffDirectoryController {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final int MIN_TOKEN_BYTES = 32;

    private final StaffStore staff;
    private final byte[] token;

    StaffDirectoryController(StaffStore staff, @Value("${staff.directory.token:}") String token) {
        this.staff = staff;
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/internal/branches/{branchId}/staff-user-ids")
    ResponseEntity<Directory> staffUserIds(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
            @PathVariable long branchId) {
        if (token.length < MIN_TOKEN_BYTES) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Staff directory is not configured");
        }
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service credential required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Directory(branchId, staff.activeUserIds(branchId)));
    }

    record Directory(long branchId, List<Long> userIds) { }
}
