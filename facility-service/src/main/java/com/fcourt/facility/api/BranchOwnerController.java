package com.fcourt.facility.api;

import com.fcourt.facility.persistence.BranchStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who owns a branch, for notification-service (in-app notifications are addressed by user id and events only carry
 * the branch id). Internal GET route like the others: the gateway does not route {@code /api/internal/**}.
 */
@RestController
class BranchOwnerController {
  private final BranchStore branches;

  BranchOwnerController(BranchStore branches) {
    this.branches = branches;
  }

  @GetMapping("/api/internal/branches/{id}/owner")
  ResponseEntity<Owner> owner(@PathVariable long id) {
    var branch =
        branches.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Owner(id, branch.adminId()));
  }

  record Owner(long branchId, long ownerId) {}
}
