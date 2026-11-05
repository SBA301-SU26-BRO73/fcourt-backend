package com.fcourt.discovery.dto.request;

import jakarta.validation.constraints.*;

public record ReplyReviewRequest(@NotBlank @Size(max=2000) String reply) {
    public ReplyReviewRequest { reply = reply == null ? null : reply.trim(); }
}
