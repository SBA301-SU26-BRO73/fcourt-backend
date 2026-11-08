package com.fcourt.facility.api;

import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.LockStore;
import com.fcourt.facility.persistence.TemplateStore;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolves whose ownership a request is checked against: the caller's, or for SUPER_ADMIN the resource owner's. */
@Component
class Owners {
    private final BranchStore branches;
    private final CourtStore courts;
    private final TemplateStore templates;
    private final LockStore locks;

    Owners(BranchStore branches, CourtStore courts, TemplateStore templates, LockStore locks) {
        this.branches = branches;
        this.courts = courts;
        this.templates = templates;
        this.locks = locks;
    }

    static boolean isSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equals(authority.getAuthority()));
    }

    long forBranch(Jwt jwt, long branchId) {
        long caller = BranchController.owner(jwt);
        if (!isSuperAdmin()) return caller;
        return branches.find(branchId).map(branch -> branch.adminId()).orElse(caller);
    }

    long forCourt(Jwt jwt, long courtId) {
        long caller = BranchController.owner(jwt);
        if (!isSuperAdmin()) return caller;
        return courts.find(courtId).map(court -> court.adminId()).orElse(caller);
    }

    long forTemplate(Jwt jwt, long templateId) {
        long caller = BranchController.owner(jwt);
        if (!isSuperAdmin()) return caller;
        return templates.findOwner(templateId).map(found -> found[2]).orElse(caller);
    }

    long forLock(Jwt jwt, long lockId) {
        long caller = BranchController.owner(jwt);
        if (!isSuperAdmin()) return caller;
        return locks.findOwner(lockId).map(found -> found[2]).orElse(caller);
    }

    long forLockGroup(Jwt jwt, UUID groupId) {
        long caller = BranchController.owner(jwt);
        if (!isSuperAdmin()) return caller;
        return locks.findGroupOwner(groupId).map(found -> found[1]).orElse(caller);
    }
}
