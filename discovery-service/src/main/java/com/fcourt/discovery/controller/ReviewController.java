package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.request.*;
import com.fcourt.discovery.dto.response.ReviewPage;
import com.fcourt.discovery.model.Review;
import com.fcourt.discovery.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service = service; }

    @PostMapping("/api/reviews")
    ResponseEntity<Review> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(request, jwt));
    }
    @GetMapping("/api/public/branches/{id}/reviews")
    ResponseEntity<ReviewPage> list(@PathVariable long id, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id, page, size));
    }
    @PostMapping("/api/admin/reviews/{id}/reply")
    ResponseEntity<Review> reply(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReplyReviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reply(id, request, jwt));
    }
}
