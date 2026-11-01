package com.fcourt.staff.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

public final class StaffRequests {
    private StaffRequests() { }

    public record Shift(@Min(1) @Max(7) int dayOfWeek,
                        @NotNull LocalTime startTime, @NotNull LocalTime endTime) { }

    public record Create(@Positive long branchId,
                         @NotBlank @Email @Size(max = 255) String email,
                         @NotBlank @Size(min = 12, max = 72) String password,
                         @NotBlank @Size(max = 150) String fullName,
                         @NotNull @Pattern(regexp = "\\+?[0-9]{9,15}") String phone,
                         @NotNull @Size(max = 28) List<@NotNull @Valid Shift> shifts) {
        @Override
        public String toString() {
            return "Create[credentials=REDACTED]";
        }
    }

    public record Update(@NotBlank @Size(max = 150) String fullName,
                         @NotNull @Pattern(regexp = "\\+?[0-9]{9,15}") String phone,
                         @NotNull @Size(max = 28) List<@NotNull @Valid Shift> shifts) { }
}
