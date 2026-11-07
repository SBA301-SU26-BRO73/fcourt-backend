package com.fcourt.facility.api;

import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Minimal public venue metadata; internal GET routes follow the existing network policy. */
@RestController
class BookingContextController {
  private final CourtStore courts;
  private final BranchStore branches;

  BookingContextController(CourtStore courts, BranchStore branches) {
    this.courts = courts;
    this.branches = branches;
  }

  @GetMapping("/api/internal/courts/{id}/booking-context")
  ResponseEntity<Context> get(@PathVariable long id) {
    var court =
        courts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var branch =
        branches
            .find(court.court().branchId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!"ACTIVE".equals(court.court().status()) || !"ACTIVE".equals(branch.status()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Venue is not available");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Context(
                id,
                branch.id(),
                court.court().name(),
                branch.name(),
                branch.address(),
                branch.phone()));
  }

  record Context(
      long courtId,
      long branchId,
      String courtName,
      String branchName,
      String branchAddress,
      String branchPhone) {}
}
