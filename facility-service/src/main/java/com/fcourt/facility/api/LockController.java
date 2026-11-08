package com.fcourt.facility.api;

import static com.fcourt.facility.api.BranchController.owner;

import com.fcourt.facility.api.LockDtos.BranchLockRequest;
import com.fcourt.facility.api.LockDtos.LockGroupResponse;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockSlotsRequest;
import com.fcourt.facility.service.LockService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Slot locks / out of service (SCRUM-49) and branch-wide private-event locks (SCRUM-111): one shared model. */
@RestController
class LockController {
    private final LockService locks;
    private final Owners owners;

    LockController(LockService locks, Owners owners) {
        this.locks = locks;
        this.owners = owners;
    }

    @PostMapping("/api/admin/courts/lock-slots")
    @ResponseStatus(HttpStatus.CREATED)
    LockResponse lockCourt(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LockSlotsRequest request) {
        return locks.lockCourt(owners.forCourt(jwt, request.courtId()), request);
    }

    @GetMapping("/api/admin/courts/lock-slots")
    List<LockResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam long branchId,
            @RequestParam(required = false) Long courtId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return locks.list(owners.forBranch(jwt, branchId), branchId, courtId, from, to);
    }

    @DeleteMapping("/api/admin/courts/lock-slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        locks.unlock(owners.forLock(jwt, id), id);
    }

    @PostMapping("/api/admin/courts/out-of-service")
    @ResponseStatus(HttpStatus.CREATED)
    LockGroupResponse lockBranch(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BranchLockRequest request) {
        return locks.lockBranch(owners.forBranch(jwt, request.branchId()), request);
    }

    @DeleteMapping("/api/admin/courts/out-of-service/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlockGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID groupId) {
        locks.unlockGroup(owners.forLockGroup(jwt, groupId), groupId);
    }
}
