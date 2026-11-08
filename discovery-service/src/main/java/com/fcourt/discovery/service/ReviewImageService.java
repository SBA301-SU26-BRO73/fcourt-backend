package com.fcourt.discovery.service;

import com.fcourt.discovery.component.ReviewIdentity;
import com.fcourt.discovery.component.ReviewImagePolicy;
import com.fcourt.discovery.component.ReviewImageStorage;
import com.fcourt.discovery.component.ReviewImageValidator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Uploads review photos for the authenticated customer; the returned URLs are the only ones POST /api/reviews accepts. */
@Service
public class ReviewImageService {
    private final ReviewImageValidator validator;
    private final ReviewImageStorage storage;
    private final ReviewImagePolicy policy;

    public ReviewImageService(ReviewImageValidator validator, ReviewImageStorage storage, ReviewImagePolicy policy) {
        this.validator = validator; this.storage = storage; this.policy = policy;
    }

    public List<String> upload(Jwt jwt, List<MultipartFile> files) {
        long user = ReviewIdentity.require(jwt, "CUSTOMER");
        var uploads = validator.validateAll(files);
        if (!policy.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình lưu trữ ảnh trên máy chủ");
        String folder = ReviewImagePolicy.folder(user);
        var urls = uploads.stream().map(upload -> storage.store(folder, upload)).toList();
        // Defence in depth: never hand out a URL that create() would later refuse.
        if (!policy.allowsAll(urls, user)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên, vui lòng thử lại");
        return urls;
    }
}
