package com.fcourt.subscription.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.subscription.common.PageResponse.Pagination;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {
    @Test
    void paginationDefaultsToTheFirstPageOfTen() {
        var pagination = Pagination.of(null, null);

        assertThat(pagination).isEqualTo(new Pagination(0, 10));
        assertThat(pagination.offset()).isZero();
    }

    @Test
    void paginationClampsNegativePagesAndOutOfRangeSizes() {
        assertThat(Pagination.of(-3, 0)).isEqualTo(new Pagination(0, 1));
        assertThat(Pagination.of(2, 1_000)).isEqualTo(new Pagination(2, 100));
        assertThat(Pagination.of(3, 20).offset()).isEqualTo(60);
    }

    @Test
    void offsetDoesNotOverflowForLargePageNumbers() {
        assertThat(new Pagination(Integer.MAX_VALUE, 100).offset()).isEqualTo((long) Integer.MAX_VALUE * 100);
    }

    @Test
    void totalPagesRoundsUpAndAnEmptyResultHasNone() {
        assertThat(PageResponse.of(List.of(), new Pagination(0, 10), 21).totalPages()).isEqualTo(3);
        assertThat(PageResponse.of(List.of(), new Pagination(0, 10), 20).totalPages()).isEqualTo(2);
        assertThat(PageResponse.of(List.of(), new Pagination(0, 10), 0).totalPages()).isZero();
    }
}
