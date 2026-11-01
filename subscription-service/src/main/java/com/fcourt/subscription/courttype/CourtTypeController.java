package com.fcourt.subscription.courttype;

import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeRequest;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reads are public; writes need SUPER_ADMIN (enforced in SecurityConfiguration). */
@RestController
@RequestMapping("/api/court-types")
class CourtTypeController {
    private final CourtTypeService service;

    CourtTypeController(CourtTypeService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<CourtTypeResponse> list(@RequestParam(required = false) Boolean active,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return service.list(active, Pagination.of(page, size));
    }

    @GetMapping("/{id}")
    CourtTypeResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CourtTypeResponse create(@Valid @RequestBody CourtTypeRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    CourtTypeResponse update(@PathVariable long id, @Valid @RequestBody CourtTypeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }
}
