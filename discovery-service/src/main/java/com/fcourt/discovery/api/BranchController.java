package com.fcourt.discovery.api;

import com.fcourt.discovery.application.BranchSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/branches")
public class BranchController {
    private final BranchSearchService service;

    public BranchController(BranchSearchService service) {
        this.service = service;
    }

    @GetMapping
    public BranchSearchResponse search(@Valid @ModelAttribute BranchSearchRequest request) {
        return service.search(request);
    }
}
