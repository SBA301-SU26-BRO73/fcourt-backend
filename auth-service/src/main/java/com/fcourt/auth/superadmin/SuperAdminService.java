package com.fcourt.auth.superadmin;

import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.superadmin.SuperAdminDtos.CourtAdminResponse;
import com.fcourt.auth.superadmin.SuperAdminDtos.UserStatusResponse;
import com.fcourt.auth.superadmin.SuperAdminRepository.UserRecord;
import com.fcourt.auth.user.UserRole;
import com.fcourt.auth.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCRUM-8 owner approval and account locking. Every decision is audited in
 * {@code user_account_action_logs}; approval also appends an
 * {@link OwnerApprovedEvent} to the outbox in the same transaction.
 */
@Service
public class SuperAdminService {
    static final String APPROVE = "APPROVE";
    static final String REJECT = "REJECT";
    static final String LOCK = "LOCK";
    static final String UNLOCK = "UNLOCK";

    private final SuperAdminRepository users;
    private final OutboxWriter outbox;
    private final Clock clock;

    SuperAdminService(SuperAdminRepository users, OutboxWriter outbox, Clock clock) {
        this.users = users;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CourtAdminResponse> pendingCourtAdmins(long actorId) {
        requireActiveSuperAdmin(actorId);
        return withDocuments(users.findPendingOwners());
    }

    @Transactional(readOnly = true)
    public CourtAdminResponse courtAdmin(long actorId, long ownerId) {
        requireActiveSuperAdmin(actorId);
        var owner = users.find(ownerId).filter(SuperAdminService::isOwner)
                .orElseThrow(() -> ApiException.notFound("Court admin not found"));
        return withDocuments(List.of(owner)).getFirst();
    }

    @Transactional
    public CourtAdminResponse approve(long actorId, long ownerId, String reason) {
        requireActiveSuperAdmin(actorId);
        var owner = pendingOwnerForUpdate(ownerId);
        Instant now = clock.instant();
        users.updateStatus(ownerId, UserStatus.ACTIVE.name(), now);
        users.logAction(ownerId, actorId, APPROVE, owner.status(), UserStatus.ACTIVE.name(), normalise(reason), now);
        outbox.append(ownerId, OwnerApprovedEvent.TYPE, OwnerApprovedEvent.VERSION,
                new OwnerApprovedEvent(ownerId, actorId, now));
        return reload(ownerId);
    }

    @Transactional
    public CourtAdminResponse reject(long actorId, long ownerId, String reason) {
        requireActiveSuperAdmin(actorId);
        String note = normalise(reason);
        if (note == null) {
            throw ApiException.badRequest("reason is required");
        }
        var owner = pendingOwnerForUpdate(ownerId);
        Instant now = clock.instant();
        users.updateStatus(ownerId, UserStatus.REJECTED.name(), now);
        users.logAction(ownerId, actorId, REJECT, owner.status(), UserStatus.REJECTED.name(), note, now);
        return reload(ownerId);
    }

    /** ACTIVE becomes LOCKED and LOCKED becomes ACTIVE; super admin accounts cannot be locked. */
    @Transactional
    public UserStatusResponse toggleStatus(long actorId, long userId, String reason) {
        requireActiveSuperAdmin(actorId);
        var user = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        if (UserRole.SUPER_ADMIN.name().equals(user.role())) {
            throw ApiException.conflict("Super admin accounts cannot be locked");
        }
        String next;
        String action;
        if (UserStatus.ACTIVE.name().equals(user.status())) {
            next = UserStatus.LOCKED.name();
            action = LOCK;
        } else if (UserStatus.LOCKED.name().equals(user.status())) {
            next = UserStatus.ACTIVE.name();
            action = UNLOCK;
        } else {
            throw ApiException.conflict("Only ACTIVE or LOCKED accounts can be toggled (current: "
                    + user.status() + ")");
        }
        Instant now = clock.instant();
        users.updateStatus(userId, next, now);
        users.logAction(userId, actorId, action, user.status(), next, normalise(reason), now);
        return new UserStatusResponse(userId, next);
    }

    /**
     * The token proves the role at issue time; the database proves the account is
     * still an ACTIVE super admin (and gives the audit log a valid performed_by_id).
     */
    private void requireActiveSuperAdmin(long actorId) {
        boolean active = users.find(actorId)
                .filter(actor -> UserRole.SUPER_ADMIN.name().equals(actor.role()))
                .filter(actor -> UserStatus.ACTIVE.name().equals(actor.status()))
                .isPresent();
        if (!active) {
            throw ApiException.forbidden("Super admin account is not active");
        }
    }

    private UserRecord pendingOwnerForUpdate(long ownerId) {
        var owner = users.findForUpdate(ownerId).filter(SuperAdminService::isOwner)
                .orElseThrow(() -> ApiException.notFound("Court admin not found"));
        if (!UserStatus.PENDING_APPROVAL.name().equals(owner.status())) {
            throw ApiException.conflict("Court admin is not pending approval (current: " + owner.status() + ")");
        }
        return owner;
    }

    private CourtAdminResponse reload(long ownerId) {
        var owner = users.find(ownerId).orElseThrow(() -> ApiException.notFound("Court admin not found"));
        return withDocuments(List.of(owner)).getFirst();
    }

    private List<CourtAdminResponse> withDocuments(List<UserRecord> owners) {
        var documents = users.documentsOf(owners.stream().map(UserRecord::id).toList());
        return owners.stream().map(owner -> new CourtAdminResponse(owner.id(), owner.email(), owner.fullName(),
                owner.phone(), owner.role(), owner.status(), owner.createdAt(),
                documents.getOrDefault(owner.id(), List.of()))).toList();
    }

    private static boolean isOwner(UserRecord user) {
        return UserRole.ADMIN.name().equals(user.role());
    }

    private static String normalise(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }
}
