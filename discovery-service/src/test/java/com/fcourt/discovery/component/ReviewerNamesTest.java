package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewerNamesTest {
    @Test
    void showsGivenNameAndFamilyInitialOnly() {
        assertThat(ReviewerNames.mask("Nguyễn Văn An")).isEqualTo("An N.");
        assertThat(ReviewerNames.mask("  Trần   Thị Bình ")).isEqualTo("Bình T.");
        assertThat(ReviewerNames.mask("đặng Minh")).isEqualTo("Minh Đ.");
    }

    @Test
    void singleWordNameIsKeptAndEmptyNamesAreUnknown() {
        assertThat(ReviewerNames.mask("Linh")).isEqualTo("Linh");
        assertThat(ReviewerNames.mask(null)).isNull();
        assertThat(ReviewerNames.mask("   ")).isNull();
        assertThat(ReviewerNames.mask("")).isNull();
    }

    @Test
    void neverLeaksMiddleNamesAndBoundsLength() {
        assertThat(ReviewerNames.mask("Nguyen Van Hoang Anh")).isEqualTo("Anh N.").doesNotContain("Van", "Hoang");
        assertThat(ReviewerNames.mask("1234 5678")).isEqualTo("5678");
        assertThat(ReviewerNames.mask("A " + "x".repeat(100))).hasSizeLessThanOrEqualTo(34);
    }
}
