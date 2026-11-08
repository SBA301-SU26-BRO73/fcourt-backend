package com.fcourt.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.discovery.component.ReviewImagePolicy;
import com.fcourt.discovery.component.ReviewImageStorage;
import com.fcourt.discovery.component.ReviewImageValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class ReviewImageServiceTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    final List<String> folders = new ArrayList<>();
    final ReviewImageStorage storage = (folder, upload) -> {
        folders.add(folder);
        return "https://res.cloudinary.com/demo/image/upload/v1/" + folder + "/" + folders.size() + ".png";
    };
    final ReviewImageService service = new ReviewImageService(new ReviewImageValidator(1024), storage, new ReviewImagePolicy("demo"), limiter(20));

    static Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).claim("roles", List.of(role)).build();
    }

    static com.fcourt.discovery.component.ReviewUploadLimiter limiter(int max) {
        return new com.fcourt.discovery.component.ReviewUploadLimiter(max, 600, java.time.Clock.systemUTC());
    }

    @Test
    void rateLimitStopsFurtherUploadsWithoutCallingStorage() {
        var limited = new ReviewImageService(new ReviewImageValidator(1024), storage, new ReviewImagePolicy("demo"), limiter(3));
        limited.upload(jwt("10", "CUSTOMER"), List.of(png(), png()));
        assertThatThrownBy(() -> limited.upload(jwt("10", "CUSTOMER"), List.of(png(), png()))).satisfies(e -> assertStatus(e, 429));
        assertThat(folders).hasSize(2);
        assertThat(limited.upload(jwt("11", "CUSTOMER"), List.of(png()))).hasSize(1);
    }

    static MockMultipartFile png() { return new MockMultipartFile("files", "../x.png", "text/plain", PNG); }

    static void assertStatus(Throwable error, int status) {
        assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void customerUploadsLandInTheirOwnServerChosenFolder() {
        var urls = service.upload(jwt("10", "CUSTOMER"), List.of(png(), png()));
        assertThat(urls).hasSize(2).allMatch(url -> url.contains("/fcourt/reviews/10/"));
        assertThat(folders).containsOnly("fcourt/reviews/10");
        assertThat(new ReviewImagePolicy("demo").allowsAll(urls, 10)).isTrue();
    }

    @Test
    void onlyCustomersMayUploadAndAnonymousIsUnauthorized() {
        assertThatThrownBy(() -> service.upload(jwt("100", "ADMIN"), List.of(png()))).satisfies(e -> assertStatus(e, 403));
        assertThatThrownBy(() -> service.upload(null, List.of(png()))).satisfies(e -> assertStatus(e, 401));
        assertThat(folders).isEmpty();
    }

    @Test
    void invalidFilesAreRejectedBeforeAnythingIsUploaded() {
        var fake = new MockMultipartFile("files", "a.png", "image/png", "<svg/>".getBytes());
        assertThatThrownBy(() -> service.upload(jwt("10", "CUSTOMER"), List.of(png(), fake))).satisfies(e -> assertStatus(e, 400));
        assertThat(folders).isEmpty();
    }

    @Test
    void answers503WhenCloudinaryIsNotConfigured() {
        var unconfigured = new ReviewImageService(new ReviewImageValidator(1024), storage, new ReviewImagePolicy(""), limiter(20));
        assertThatThrownBy(() -> unconfigured.upload(jwt("10", "CUSTOMER"), List.of(png()))).satisfies(e -> assertStatus(e, 503));
    }

    @Test
    void refusesToHandOutUrlsOutsideTheAllowlist() {
        var rogue = new ReviewImageService(new ReviewImageValidator(1024), (folder, upload) -> "https://evil.test/a.png", new ReviewImagePolicy("demo"), limiter(20));
        assertThatThrownBy(() -> rogue.upload(jwt("10", "CUSTOMER"), List.of(png()))).satisfies(e -> assertStatus(e, 502));
    }
}
