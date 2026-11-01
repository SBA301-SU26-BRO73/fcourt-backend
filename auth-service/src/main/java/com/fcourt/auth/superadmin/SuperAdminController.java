package com.fcourt.auth.superadmin;

import com.fcourt.auth.superadmin.SuperAdminDtos.CourtAdminResponse;
import com.fcourt.auth.superadmin.SuperAdminDtos.ReasonRequest;
import com.fcourt.auth.superadmin.SuperAdminDtos.RejectRequest;
import com.fcourt.auth.superadmin.SuperAdminDtos.UserStatusResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN only (enforced in SecurityConfiguration and re-checked against the database). */
@RestController
@RequestMapping("/api/super-admin")
class SuperAdminController {
    private final SuperAdminService service;

    SuperAdminController(SuperAdminService service) {
        this.service = service;
    }

    @GetMapping("/court-admins/pending")
    List<CourtAdminResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return service.pendingCourtAdmins(actor(jwt));
    }

    @GetMapping("/court-admins/{id}")
    CourtAdminResponse courtAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.courtAdmin(actor(jwt), id);
    }

    /** Also mapped as {@code /users/{id}/approve}, the route used by the team system test. */
    @PutMapping({"/court-admins/{id}/approve", "/users/{id}/approve"})
    CourtAdminResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                               @Valid @RequestBody(required = false) ReasonRequest body) {
        return service.approve(actor(jwt), id, body == null ? null : body.reason());
    }

    @PutMapping("/court-admins/{id}/reject")
    CourtAdminResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                              @Valid @RequestBody RejectRequest body) {
        return service.reject(actor(jwt), id, body.reason());
    }

    @PutMapping("/users/{id}/toggle-status")
    UserStatusResponse toggleStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @Valid @RequestBody(required = false) ReasonRequest body) {
        return service.toggleStatus(actor(jwt), id, body == null ? null : body.reason());
    }

    private static long actor(Jwt jwt) {
        // The JwtDecoder already guarantees a positive numeric subject.
        return Long.parseLong(jwt.getSubject());
    }
}
