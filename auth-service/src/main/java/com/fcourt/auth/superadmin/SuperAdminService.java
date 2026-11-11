package com.fcourt.auth.superadmin;

import com.fcourt.auth.account.DocumentStorage;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.superadmin.SuperAdminDtos.DocumentResponse;
import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.superadmin.SuperAdminDtos.CourtAdminResponse;
import com.fcourt.auth.superadmin.SuperAdminDtos.UserListItem;
import com.fcourt.auth.superadmin.SuperAdminDtos.UserPage;
import com.fcourt.auth.superadmin.SuperAdminDtos.UserStatusResponse;
import com.fcourt.auth.superadmin.SuperAdminRepository.UserRecord;
import com.fcourt.auth.user.UserRole;
import com.fcourt.auth.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    /** Documents are private: the response carries short-lived presigned URLs, never the stored key. */
    private final DocumentStorage storage;

    SuperAdminService(SuperAdminRepository users, OutboxWriter outbox, Clock clock, DocumentStorage storage) {
        this.storage = storage;
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

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 100;

    /** Paged user list, newest first. SUPER_ADMIN accounts are neither listed nor searchable. */
    @Transactional(readOnly = true)
    public UserPage listUsers(long actorId, String role, String status, String query, int page, int size) {
        requireActiveSuperAdmin(actorId);
        if (page < 0) {
            throw ApiException.badRequest("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String roleFilter = enumFilter(role, "role", UserRole.class);
        if (UserRole.SUPER_ADMIN.name().equals(roleFilter)) {
            throw ApiException.badRequest("role must be one of CUSTOMER, STAFF, ADMIN");
        }
        String statusFilter = enumFilter(status, "status", UserStatus.class);
        String term = normalise(query);
        if (term != null && term.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("query must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        String pattern = term == null ? null : "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
        long total = users.countSearch(roleFilter, statusFilter, pattern);
        var items = users.search(roleFilter, statusFilter, pattern, size, (long) page * size).stream()
                .map(user -> new UserListItem(user.id(), user.email(), user.fullName(), user.phone(), user.role(),
                        user.status(), user.createdAt()))
                .toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new UserPage(items, page, size, total, totalPages);
    }

    private static <E extends Enum<E>> String enumFilter(String value, String name, Class<E> type) {
        String text = normalise(value);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException unknown) {
            throw ApiException.badRequest("Unknown " + name + ": " + text);
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional
    public CourtAdminResponse approve(long actorId, long ownerId, String reason) {
        requireActiveSuperAdmin(actorId);
        var owner = pendingOwnerForUpdate(ownerId);
        Instant now = clock.instant();
        users.updateStatus(ownerId, UserStatus.ACTIVE.name(), now);
        users.logAction(ownerId, actorId, APPROVE, owner.status(), UserStatus.ACTIVE.name(), normalise(reason), now);
        outbox.append(ownerId, OwnerApprovedEvent.TYPE, OwnerApprovedEvent.VERSION,
                new OwnerApprovedEvent(ownerId, actorId, now, owner.email(), owner.fullName()));
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
            if (normalise(reason) == null) {
                throw ApiException.badRequest("reason is required to lock an account");
            }
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
                documents.getOrDefault(owner.id(), List.of()).stream()
                        .map(document -> new DocumentResponse(document.id(), storage.signedUrl(document.url()),
                                document.docType())).toList())).toList();
    }

    private static boolean isOwner(UserRecord user) {
        return UserRole.ADMIN.name().equals(user.role());
    }

    private static String normalise(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }
}
