package com.fcourt.subscription.plan;

import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanRequest;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * {@code /all} (plans on sale) is public; the paged list and {@code /{id}} need a signed-in user;
 * writes need SUPER_ADMIN. Enforced in SecurityConfiguration.
 */
@RestController
@RequestMapping("/api/subscription-plans")
class PlanController {
    private final PlanService service;

    PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<SubscriptionPlanResponse> list(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return service.list(Pagination.of(page, size));
    }

    @GetMapping("/all")
    List<SubscriptionPlanResponse> listActive() {
        return service.listActive();
    }

    @GetMapping("/{id}")
    SubscriptionPlanResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SubscriptionPlanResponse create(@Valid @RequestBody SubscriptionPlanRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    SubscriptionPlanResponse update(@PathVariable long id, @Valid @RequestBody SubscriptionPlanRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }
}
