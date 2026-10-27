package com.fcourt.discovery.api;

import com.fcourt.discovery.persistence.BranchSearchRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/branches")
public class BranchController {
    private final BranchSearchRepository repository;

    public BranchController(BranchSearchRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public BranchSearchResponse search(@Valid @ModelAttribute BranchSearchRequest request) {
        return repository.search(request);
    }
}
