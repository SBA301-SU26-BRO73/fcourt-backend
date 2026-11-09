package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CourtImageServiceTest {
    @Test
    void ownKeyOnlyMatchesFilesInTheCourtsOwnFolder() {
        assertThat(CourtImageService.ownKey("https://cdn.x/branches/3/courts/7/a.png", 3, 7)).isEqualTo("branches/3/courts/7/a.png");
        assertThat(CourtImageService.ownKey("https://cdn.x/branches/3/courts/70/a.png", 3, 7)).isNull();
        assertThat(CourtImageService.ownKey("https://cdn.x/branches/3/a.png", 3, 7)).isNull();
        assertThat(CourtImageService.ownKey("https://cdn.x/branches/4/courts/7/a.png", 3, 7)).isNull();
        assertThat(CourtImageService.ownKey("https://cdn.x/branches/3/courts/7/../../a.png", 3, 7)).isNull();
        assertThat(CourtImageService.ownKey("https://example.com/c.jpg", 3, 7)).isNull();
        assertThat(CourtImageService.ownKey(null, 3, 7)).isNull();
    }
}
