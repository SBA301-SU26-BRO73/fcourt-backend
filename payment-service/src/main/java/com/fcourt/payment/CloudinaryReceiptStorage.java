package com.fcourt.payment;

import com.cloudinary.Cloudinary;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class CloudinaryReceiptStorage implements ReceiptStorage {
    private final String url;
    CloudinaryReceiptStorage(@Value("${payment.cloudinary-url:}") String url) { this.url = url; }

    @Override
    public String upload(byte[] bytes) {
        if (url.isBlank()) throw unavailable();
        try {
            var result = new Cloudinary(url).uploader().upload(bytes, Map.of(
                    "resource_type", "image", "folder", "fcourt/receipts",
                    "public_id", UUID.randomUUID().toString(), "overwrite", false,
                    "timeout", 15000));
            String secureUrl = (String) result.get("secure_url");
            URI uri = URI.create(secureUrl);
            if (!"https".equals(uri.getScheme()) || !"res.cloudinary.com".equals(uri.getHost())) {
                throw unavailable();
            }
            return secureUrl;
        } catch (Exception e) {
            // Never include SDK error details: these can contain signing credentials.
            throw unavailable();
        }
    }
    @Override
    public void delete(String url) {
        if (this.url.isBlank() || url == null) return;
        try {
            String path = URI.create(url).getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            String publicId = "fcourt/receipts/" + (dot > 0 ? file.substring(0, dot) : file);
            new Cloudinary(this.url).uploader().destroy(publicId, Map.of("resource_type", "image", "invalidate", true));
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass()).warn("Receipt cleanup failed, image left for retention job");
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt storage unavailable");
    }
}
