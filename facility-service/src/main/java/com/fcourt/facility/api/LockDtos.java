package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Court lock API (SCRUM-49 slot lock / out of service, SCRUM-111 branch-wide and private-event locks).
 * One model: a lock is a venue-local window [startAt, endAt) on one court.
 */
public final class LockDtos {
    private LockDtos() { }

    public enum LockType { OUT_OF_SERVICE, PRIVATE_EVENT }

    /** Fields shared by both create requests. reason/notes are internal; the last three are public. */
    public interface LockDetails {
        LocalDateTime startAt();
        LocalDateTime endAt();
        LockType type();
        String reason();
        String notes();
        String publicReason();
        String contactName();
        String contactPhone();
    }

    public record LockSlotsRequest(
            @NotNull @Positive Long courtId,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            LockType type,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 1000) String notes,
            @Size(max = 500) String publicReason,
            @Size(max = 100) String contactName,
            @Size(max = 100) @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "must be 9-15 digits, optional leading +")
            String contactPhone) implements LockDetails { }

    public record BranchLockRequest(
            @NotNull @Positive Long branchId,
            Boolean allCourts,
            List<@NotNull @Positive Long> courtIds,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            LockType type,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 1000) String notes,
            @Size(max = 500) String publicReason,
            @Size(max = 100) String contactName,
            @Size(max = 100) @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "must be 9-15 digits, optional leading +")
            String contactPhone) implements LockDetails {

        @JsonIgnore
        @AssertTrue(message = "choose allCourts=true or a non-empty courtIds list without duplicates")
        public boolean isScopeValid() {
            boolean all = Boolean.TRUE.equals(allCourts);
            boolean listed = courtIds != null && !courtIds.isEmpty()
                    && courtIds.stream().distinct().count() == courtIds.size();
            return all ? courtIds == null || courtIds.isEmpty() : listed;
        }
    }

    public record LockResponse(long id, UUID groupId, long branchId, long courtId, LockType type,
                               LocalDateTime startAt, LocalDateTime endAt, String reason, String notes,
                               String publicReason, String contactName, String contactPhone,
                               long createdBy, OffsetDateTime createdAt) { }

    public record LockGroupResponse(UUID groupId, long branchId, int totalLocks, List<LockResponse> locks) { }

    /** Public, customer-safe part of a lock; never contains reason or notes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Restriction(LockType type, String publicReason, String contactName, String contactPhone) { }

    public record SlotConflict(long courtId, LocalDate date, long slotId,
                               @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                               @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                               String status) { }
}
