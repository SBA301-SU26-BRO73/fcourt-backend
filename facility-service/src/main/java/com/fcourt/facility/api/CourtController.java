package com.fcourt.facility.api;

import static com.fcourt.facility.api.BranchController.owner;

import com.fcourt.facility.service.CourtService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Court API (SCRUM-16), scoped to branches owned by the JWT subject. */
@RestController
class CourtController {
    private final CourtService courts;

    CourtController(CourtService courts) {
        this.courts = courts;
    }

    @GetMapping("/api/admin/branches/{branchId}/courts")
    List<CourtResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long branchId) {
        return courts.list(owner(jwt), branchId);
    }

    @PostMapping("/api/admin/branches/{branchId}/courts")
    @ResponseStatus(HttpStatus.CREATED)
    CourtResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable long branchId,
                         @Valid @RequestBody CourtRequest request) {
        return courts.create(owner(jwt), jwt.getTokenValue(), branchId, request);
    }

    @GetMapping("/api/admin/courts/{id}")
    CourtResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return courts.get(owner(jwt), id);
    }

    @PutMapping("/api/admin/courts/{id}")
    CourtResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                         @Valid @RequestBody CourtRequest request) {
        return courts.update(owner(jwt), id, request);
    }

    @PatchMapping("/api/admin/courts/{id}/status")
    CourtResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                               @Valid @RequestBody CourtRequest.StatusChange request) {
        return courts.changeStatus(owner(jwt), id, request.status());
    }

    @DeleteMapping("/api/admin/courts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        courts.delete(owner(jwt), id);
    }

    @GetMapping("/api/staff/courts/{id}")
    CourtResponse.StaffView staffCourt(@PathVariable long id) {
        return courts.staffCourt(id);
    }
}
