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
        assertThat(ReviewerNames.mask("1234 5678")).isNull();
        assertThat(ReviewerNames.mask("A " + "x".repeat(100))).hasSizeLessThanOrEqualTo(34);
    }

    @Test
    void emailsPhonesAndSymbolsTypedAsNamesAreNeverPublished() {
        assertThat(ReviewerNames.mask("john.doe@example.com")).isNull();
        assertThat(ReviewerNames.mask("0909123456")).isNull();
        assertThat(ReviewerNames.mask("Nguyen 0909123456")).isEqualTo("Nguyen");
        assertThat(ReviewerNames.mask("https://evil.test/x")).isNull();
        assertThat(ReviewerNames.mask("<script>alert(1)</script>")).isNull();
        assertThat(ReviewerNames.mask("😀😀")).isNull();
    }

    @Test
    void unicodeFormattingAndSpacingEdgeCases() {
        assertThat(ReviewerNames.mask("Nguy​ễn Văn　An")).isEqualTo("An N.");
        assertThat(ReviewerNames.mask("Nguyễn Van An")).isEqualTo("An N.");
        assertThat(ReviewerNames.mask("Trần\nThị\tBình")).isEqualTo("Bình T.");
        assertThat(ReviewerNames.mask("‮evil Name")).isEqualTo("Name E.");
        assertThat(ReviewerNames.mask("Mary-Jane O’Neil")).isEqualTo("O’Neil M.");
    }
}
