package com.fcourt.hold;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HoldController {
    private final HoldService service;

    HoldController(HoldService service) { this.service = service; }

    @PostMapping("/api/bookings/hold-slots")
    @ResponseStatus(HttpStatus.CREATED)
    HoldResponse hold(@Valid @RequestBody HoldRequest request) {
        return service.hold(request);
    }
}
