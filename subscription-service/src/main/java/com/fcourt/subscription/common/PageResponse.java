package com.fcourt.subscription.common;

import java.util.List;

/** One page of a list endpoint: {@code page} is zero-based. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> of(List<T> content, Pagination pagination, long totalElements) {
        int totalPages = (int) ((totalElements + pagination.size() - 1) / pagination.size());
        return new PageResponse<>(content, pagination.page(), pagination.size(), totalElements, totalPages);
    }

    /** Requested page and size, clamped so a client cannot ask for a negative page or an unbounded page. */
    public record Pagination(int page, int size) {
        public static Pagination of(Integer page, Integer size) {
            int safePage = page == null ? 0 : Math.max(page, 0);
            int safeSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
            return new Pagination(safePage, safeSize);
        }

        public long offset() {
            return (long) page * size;
        }
    }
}
