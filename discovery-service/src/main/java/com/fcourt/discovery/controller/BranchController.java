package com.fcourt.discovery.controller;

import com.fcourt.discovery.service.BranchSearchService;
import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/branches")
public class BranchController {
    private final BranchSearchService service;

    public BranchController(BranchSearchService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<BranchSearchResponse> search(@Valid @ModelAttribute BranchSearchRequest request) {
        var response = ResponseEntity.ok();
        if (request.requiresAvailability()) response.cacheControl(CacheControl.noStore());
        return response.body(service.search(request));
    }
}
