package com.fcourt.discovery.controller;

import com.fcourt.discovery.service.BranchSearchService;
import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
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
