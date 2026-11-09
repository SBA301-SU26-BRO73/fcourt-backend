package com.fcourt.voucher.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Branch row returned by facility-service {@code GET /api/admin/branches}: only the fields voucher needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilityBranch(Long id, Long adminId) {
}
