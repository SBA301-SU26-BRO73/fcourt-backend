package com.fcourt.auth.account;

import com.cloudinary.Cloudinary;
import com.fcourt.auth.common.ApiException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CloudinaryDocumentStorage implements DocumentStorage {
    private static final String UNAVAILABLE = "Document storage is unavailable";

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
            if (!"https".equals(uri.getScheme()) || !"res.cloudinary.com".equals(uri.getHost())) {
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
}
