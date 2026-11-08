package com.fcourt.facility.api;

import java.time.Instant;

/** One photo of a branch gallery; {@code url} is the CDN link clients display. */
public record BranchImageResponse(long id, long branchId, String url, int sortOrder, Instant createdAt) { }
