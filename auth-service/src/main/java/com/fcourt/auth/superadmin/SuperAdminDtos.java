package com.fcourt.auth.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response shapes of the super admin API (see CONTRACTS.md, auth-service). */
public final class SuperAdminDtos {
    private SuperAdminDtos() {
    }

    /** Court owner (role ADMIN) as reviewed by a super admin, with uploaded documents. */
    public record CourtAdminResponse(long id, String email, String fullName, String phone, String role,
                                     String status, OffsetDateTime createdAt, List<DocumentResponse> documents) {
    }

    /** {@code docType}: LEGAL (business licence / identity document) or COURT_IMAGE. */
    public record DocumentResponse(long id, String url, String docType) {
    }

    /** One row of the super admin user list; never carries credentials or documents. */
    public record UserListItem(long id, String email, String fullName, String phone, String role, String status,
                               OffsetDateTime createdAt) {
    }

    /** Spring-style page envelope, the shape fcourt-web already reads ({@code number} is zero based). */
    public record UserPage(List<UserListItem> content, int number, int size, long totalElements, int totalPages) {
    }

    public record UserStatusResponse(long id, String status) {
    }

    /** Optional audit note for approve and toggle-status. */
    public record ReasonRequest(@Size(max = 500, message = "reason must be at most 500 characters") String reason) {
    }

    /** Mandatory reason shown to the rejected owner. */
    public record RejectRequest(
            @NotBlank(message = "reason is required")
            @Size(max = 500, message = "reason must be at most 500 characters") String reason) {
    }
}
