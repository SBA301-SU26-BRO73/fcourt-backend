package com.fcourt.facility.api;

import com.fcourt.facility.service.BranchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Branch API (SCRUM-14). The owner is always the JWT subject, never a request field. */
@RestController
class BranchController {
    private final BranchService branches;
    private final Owners owners;

    BranchController(BranchService branches, Owners owners) {
        this.branches = branches;
        this.owners = owners;
    }

    static long owner(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @GetMapping("/api/admin/branches")
    List<BranchResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return Owners.isSuperAdmin() ? branches.listAll() : branches.list(owner(jwt));
    }

    @PostMapping("/api/admin/branches")
    @ResponseStatus(HttpStatus.CREATED)
    BranchResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BranchRequest request) {
        if (Owners.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN cannot own branches");
        }
        return branches.create(owner(jwt), jwt.getTokenValue(), request);
    }

    @GetMapping("/api/admin/branches/{id}")
    BranchResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return branches.get(owners.forBranch(jwt, id), id);
    }

    @PutMapping("/api/admin/branches/{id}")
    BranchResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @Valid @RequestBody BranchRequest request) {
        return branches.update(owners.forBranch(jwt, id), id, request);
    }

    @DeleteMapping("/api/admin/branches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        branches.delete(owners.forBranch(jwt, id), id);
    }

    /** Bank details for payment-service's VietQR checkout; no authentication. */
    @GetMapping("/api/public/branches/{id}")
    BranchResponse.Public publicBranch(@PathVariable long id) {
        return branches.publicBranch(id);
    }

    /** No staff-assignment check here: staff-operations-service verifies the assignment first (see its CONTRACTS.md). */
    @GetMapping("/api/staff/branches/{id}")
    BranchResponse.Status staffBranch(@PathVariable long id) {
        return branches.staffBranch(id);
    }
}
