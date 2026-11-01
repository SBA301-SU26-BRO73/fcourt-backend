package com.fcourt.auth.account;

import com.cloudinary.Cloudinary;
import com.fcourt.auth.common.ApiException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CloudinaryDocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(CloudinaryDocumentStorage.class);
    private static final String UNAVAILABLE = "Document storage is unavailable";
    private static final String HOST = "res.cloudinary.com";

    private final String cloudinaryUrl;

    CloudinaryDocumentStorage(@Value("${auth.cloudinary-url:}") String cloudinaryUrl) {
        this.cloudinaryUrl = cloudinaryUrl;
    }

    @Override
    public String store(byte[] content, String folder) {
        if (cloudinaryUrl.isBlank()) {
            throw ApiException.unavailable(UNAVAILABLE);
        }
        try {
            var result = new Cloudinary(cloudinaryUrl).uploader().upload(content, Map.of(
                    "resource_type", "auto", "folder", "fcourt/" + folder,
                    "public_id", UUID.randomUUID().toString(), "overwrite", false, "timeout", 15000));
            String secureUrl = (String) result.get("secure_url");
            URI uri = URI.create(secureUrl);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
                throw ApiException.unavailable(UNAVAILABLE);
            }
            return secureUrl;
        } catch (ApiException unavailable) {
            throw unavailable;
        } catch (Exception error) {
            // SDK errors can echo signing credentials, so none of the detail is propagated.
            throw ApiException.unavailable(UNAVAILABLE);
        }
    }

    @Override
    public void delete(String url) {
        var asset = Asset.parse(url);
        if (cloudinaryUrl.isBlank() || asset.isEmpty()) {
            log.warn("Orphaned upload left in document storage: {}", url);
            return;
        }
        try {
            new Cloudinary(cloudinaryUrl).uploader().destroy(asset.get().publicId(),
                    Map.of("resource_type", asset.get().resourceType(), "invalidate", true, "timeout", 15000));
        } catch (Exception error) {
            // As in store(), the SDK message may carry credentials: log only the URL.
            log.warn("Could not delete orphaned upload from document storage: {}", url);
        }
    }

    /**
     * Public id and resource type of a delivery URL such as
     * {@code https://res.cloudinary.com/<cloud>/image/upload/v17/fcourt/legal/5/<uuid>.pdf}:
     * the version segment is dropped and so is the extension, which Cloudinary adds for image/video.
     */
    record Asset(String resourceType, String publicId) {
        static Optional<Asset> parse(String url) {
            try {
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPath() == null) {
                    return Optional.empty();
                }
                List<String> segments = new ArrayList<>(Arrays.asList(uri.getPath().substring(1).split("/")));
                if (segments.size() < 4 || !"upload".equals(segments.get(2))) {
                    return Optional.empty();
                }
                String resourceType = segments.get(1);
                List<String> rest = segments.subList(3, segments.size());
                if (rest.get(0).matches("v\\d+")) {
                    rest = rest.subList(1, rest.size());
                }
                if (rest.isEmpty()) {
                    return Optional.empty();
                }
                String publicId = String.join("/", rest);
                if (!"raw".equals(resourceType)) {
                    int dot = publicId.lastIndexOf('.');
                    if (dot > publicId.lastIndexOf('/')) {
                        publicId = publicId.substring(0, dot);
                    }
                }
                return Optional.of(new Asset(resourceType, publicId));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }
    }
}
