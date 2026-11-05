package com.fcourt.facility.api;

import com.fcourt.facility.persistence.BranchImageStore;
import com.fcourt.facility.service.BranchImageService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Branch photo gallery API (SCRUM-47); ownership is resolved like every other branch endpoint. */
@RestController
class BranchImageController {
    private final BranchImageService service;
    private final BranchImageStore images;
    private final Owners owners;

    BranchImageController(BranchImageService service, BranchImageStore images, Owners owners) {
        this.service = service;
        this.images = images;
        this.owners = owners;
    }

    @GetMapping("/api/admin/branches/{id}/images")
    List<BranchImageResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.list(owners.forBranch(jwt, id), id);
    }

    @PostMapping(value = "/api/admin/branches/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    List<BranchImageResponse> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                     @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return service.upload(owners.forBranch(jwt, id), id, files);
    }

    @DeleteMapping("/api/admin/branches/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long imageId) {
        long branchId = images.find(imageId).map(found -> found.image().branchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        service.delete(owners.forBranch(jwt, branchId), imageId);
    }

    /** Gallery of a live branch for customers; no authentication, like the other public branch read. */
    @GetMapping("/api/public/branches/{id}/images")
    List<BranchImageResponse> publicList(@PathVariable long id) {
        return service.publicList(id);
    }
}
