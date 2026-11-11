package com.fcourt.hold;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HoldController {
    private final HoldService service;

    HoldController(HoldService service) { this.service = service; }

    @PostMapping("/api/bookings/hold-slots")
    @ResponseStatus(HttpStatus.CREATED)
    HoldResponse hold(@Valid @RequestBody HoldRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean signedIn = authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7;
        if (request.guestPhone() == null && !signedIn) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "guestPhone is required when not signed in");
        }
        return service.hold(request);
    }

    @DeleteMapping("/api/bookings/hold-slots/{holdToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID holdToken) {
        service.release(holdToken);
    }
}
