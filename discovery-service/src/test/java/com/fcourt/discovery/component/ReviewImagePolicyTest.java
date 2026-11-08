package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewImagePolicyTest {
    final ReviewImagePolicy policy = new ReviewImagePolicy("https://res.cloudinary.com/demo/image/upload/", "fcourt/");
    static final String OK = "https://res.cloudinary.com/demo/image/upload/v1700000000/fcourt/reviews/10/abc.jpg";

    @Test
    void acceptsOnlyOwnUploadsInTheCallersFolder() {
        assertThat(policy.allows(OK, 10)).isTrue();
        assertThat(policy.allows("https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/abc.jpg", 10)).isTrue();
        assertThat(policy.allows(OK, 11)).as("another user folder").isFalse();
        assertThat(policy.allows(OK.replace("/10/", "/100/"), 10)).as("prefix of the id is not the id").isFalse();
    }

    @Test
    void rejectsArbitraryOrManipulatedUrls() {
        for (String url : List.of(
                "https://example.test/court.jpg",
                "http://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/other/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com.evil.test/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://user:pw@res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com:8443/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg?x=1",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg#frag",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/../11/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/%2e%2e/11/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/w_9999/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/",
                "https://res.cloudinary.com/demo/raw/upload/fcourt/reviews/10/a.jpg",
                "data:image/png;base64,abc", "not a url", "")) {
            assertThat(policy.allows(url, 10)).as(url).isFalse();
        }
        assertThat(policy.allows(null, 10)).isFalse();
    }

    @Test
    void everythingIsRejectedWhenCloudinaryIsNotConfigured() {
        assertThat(new ReviewImagePolicy("", "").allows(OK, 10)).isFalse();
        assertThat(new ReviewImagePolicy(null, null).configured()).isFalse();
        assertThat(new ReviewImagePolicy("", "").allowsAll(List.of(), 10)).as("no photos is always fine").isTrue();
    }

    @Test
    void rejectsHostAndSyntaxTricks() {
        for (String url : List.of(
                "https://res.cloudinary.com./demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com@evil.test/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://evil.test\\@res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg\n",
                " https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com//demo/image/upload/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/v1/v2/fcourt/reviews/10/a.jpg",
                "https://res.cloudinary.com/demo/image/upload/fcourt/reviews/010/a.jpg",
                "//res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg",
                "HTTPS:res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg")) {
            assertThat(policy.allows(url, 10)).as(url).isFalse();
        }
        assertThat(policy.allows("HTTPS://RES.CLOUDINARY.COM/demo/image/upload/fcourt/reviews/10/a.jpg", 10)).as("scheme and host are case-insensitive").isTrue();
    }

    @Test
    void worksForAnyProviderThroughTheConfiguredBaseUrlAndPrefix() {
        var cdn = new ReviewImagePolicy("https://cdn.example.test/media", "");
        assertThat(cdn.allows("https://cdn.example.test/media/reviews/10/a.jpg", 10)).isTrue();
        assertThat(cdn.allows("https://cdn.example.test/media/reviews/11/a.jpg", 10)).isFalse();
        assertThat(cdn.allows("https://cdn.example.test/other/reviews/10/a.jpg", 10)).isFalse();
        assertThat(cdn.allows("https://cdn.example.test/media/avatars/10/a.jpg", 10)).as("other key space").isFalse();
        assertThat(cdn.allows("https://evil.test/media/reviews/10/a.jpg", 10)).isFalse();
        assertThat(cdn.allows("https://cdn.example.test/media/reviews/10/../11/a.jpg", 10)).isFalse();
        assertThat(cdn.allows("https://res.cloudinary.com/demo/image/upload/fcourt/reviews/10/a.jpg", 10)).as("other provider").isFalse();
    }

    @Test
    void anInsecureOrMalformedBaseMeansNotConfigured() {
        for (String base : new String[] {"http://cdn.example.test/", "cdn.example.test", "https://u:p@cdn.example.test/", "https://cdn.example.test:8443/", "https://cdn.example.test/?x=1", " "}) {
            assertThat(new ReviewImagePolicy(base, "").configured()).as(base).isFalse();
        }
    }

    @Test
    void newKeysAreProviderNeutralAndInTheUsersOwnSpace() {
        String key = ReviewImagePolicy.newKey(10, "png");
        assertThat(key).matches("reviews/10/[0-9a-f-]{36}\\.png");
        assertThat(ReviewImagePolicy.newKey(10, "png")).isNotEqualTo(key);
    }

    @Test
    void allowsAllRequiresEveryUrl() {
        assertThat(policy.allowsAll(List.of(OK, OK), 10)).isTrue();
        assertThat(policy.allowsAll(List.of(OK, "https://example.test/a.jpg"), 10)).isFalse();
    }
}
