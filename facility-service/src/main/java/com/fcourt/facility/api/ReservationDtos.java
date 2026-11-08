package com.fcourt.facility.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Internal full-court reservation protocol (SCRUM-241), driven by booking-service. */
public final class ReservationDtos {
    private ReservationDtos() { }

    public static final int DEFAULT_TTL_SECONDS = 120;

    public enum State { RESERVED, COMMITTED, CANCELLED }

    public enum CancelCause { ROLLBACK, EXPIRED }

    /**
     * One window [startAt, endAt) held on every listed court. {@code adminId} is the branch owner the caller already
     * authenticated; facility re-checks it against the branch. {@code ttlSeconds} only bounds the uncommitted lease.
     * {@code relock=true} (repair of a lost lease) re-establishes the lock for bookings the caller itself already holds on
     * these courts, so the booking/hold occupancy pre-check is skipped; overlap with other locks is still refused.
     */
    public record ReserveRequest(
            @NotNull UUID reservationId,
            @NotNull @Positive Long branchId,
            @NotNull @Positive Long adminId,
            @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> courtIds,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            @Min(30) @Max(600) Integer ttlSeconds,
            @Size(max = 500) String publicReason,
            Boolean relock) {
        public ReserveRequest(UUID reservationId, Long branchId, Long adminId, List<Long> courtIds, LocalDateTime startAt,
                              LocalDateTime endAt, Integer ttlSeconds, String publicReason) {
            this(reservationId, branchId, adminId, courtIds, startAt, endAt, ttlSeconds, publicReason, null);
        }

        public boolean relocking() {
            return Boolean.TRUE.equals(relock);
        }
    }

    public record ReservationResponse(UUID reservationId, Long branchId, State state, List<Long> courtIds,
                                      LocalDateTime startAt, LocalDateTime endAt, OffsetDateTime expiresAt,
                                      OffsetDateTime committedAt, OffsetDateTime cancelledAt, CancelCause cancelCause) { }
}
