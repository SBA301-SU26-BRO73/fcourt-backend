package com.fcourt.facility.api;

import com.fcourt.facility.service.CourtImageService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Upload, replace or remove the photo of one court; ownership is resolved like every other court endpoint. */
@RestController
class CourtImageController {
    private final CourtImageService service;
    private final Owners owners;

    CourtImageController(CourtImageService service, Owners owners) {
        this.service = service;
        this.owners = owners;
    }

    @PostMapping(value = "/api/admin/courts/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CourtResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                         @RequestPart(name = "file", required = false) MultipartFile file) {
        return service.replace(owners.forCourt(jwt, id), id, file);
    }

    @DeleteMapping("/api/admin/courts/{id}/image")
    CourtResponse delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.remove(owners.forCourt(jwt, id), id);
    }
}
