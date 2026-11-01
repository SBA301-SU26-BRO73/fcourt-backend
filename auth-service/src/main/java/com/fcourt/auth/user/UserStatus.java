package com.fcourt.auth.user;

/**
 * Account lifecycle stored in {@code users.status}. Court owners register as
 * PENDING_APPROVAL and a super admin moves them to ACTIVE or REJECTED; ACTIVE
 * and LOCKED toggle. INACTIVE is kept for V1 compatibility.
 */
public enum UserStatus {
    PENDING_APPROVAL, ACTIVE, REJECTED, LOCKED, INACTIVE
}
