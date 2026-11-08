package com.fcourt.auth.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.superadmin.PlatformStatsService.MonthCount;
import com.fcourt.auth.superadmin.SuperAdminRepository.UserRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlatformStatsServiceTest {
    static final long ACTOR = 16;

    PlatformStatsRepository stats;
    SuperAdminRepository users;
    PlatformStatsService service;

    @BeforeEach
    void setUp() {
        stats = mock(PlatformStatsRepository.class);
        users = mock(SuperAdminRepository.class);
        service = new PlatformStatsService(stats, users);
        when(users.find(ACTOR)).thenReturn(Optional.of(user(ACTOR, "SUPER_ADMIN", "ACTIVE")));
    }

    @Test
    void foldsStatusCountsIntoOwnerAndCustomerFigures() {
        when(stats.countByStatus("ADMIN")).thenReturn(Map.of("ACTIVE", 3L, "PENDING_APPROVAL", 2L, "REJECTED", 1L));
        when(stats.countByStatus("CUSTOMER")).thenReturn(Map.of("ACTIVE", 10L, "LOCKED", 1L));
        var months = new LinkedHashMap<String, Long>();
        months.put("2026-09", 4L);
        months.put("2026-10", 7L);
        when(stats.newAccountsByMonth(any(), anyString())).thenReturn(months);

        var result = service.accounts(ACTOR, LocalDate.of(2026, 9, 1), "Asia/Ho_Chi_Minh");

        assertThat(result.owners().active()).isEqualTo(3);
        assertThat(result.owners().pending()).isEqualTo(2);
        assertThat(result.owners().rejected()).isEqualTo(1);
        assertThat(result.owners().total()).isEqualTo(6);
        assertThat(result.customers().total()).isEqualTo(11);
        assertThat(result.customers().active()).isEqualTo(10);
        assertThat(result.newAccountsByMonth()).containsExactly(new MonthCount("2026-09", 4), new MonthCount("2026-10", 7));
    }

    @Test
    void monthWindowStartsAtLocalMidnightOfTheRequestedZone() {
        when(stats.countByStatus(anyString())).thenReturn(Map.of());
        when(stats.newAccountsByMonth(any(), anyString())).thenReturn(Map.of());

        service.accounts(ACTOR, LocalDate.of(2026, 9, 1), "Asia/Ho_Chi_Minh");

        org.mockito.Mockito.verify(stats).newAccountsByMonth(Instant.parse("2026-08-31T17:00:00Z"), "Asia/Ho_Chi_Minh");
    }

    @Test
    void rejectsInvalidTimeZone() {
        assertThatThrownBy(() -> service.accounts(ACTOR, LocalDate.of(2026, 9, 1), "Mars/Base"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void onlyAnActiveSuperAdminMayReadStats() {
        when(users.find(5L)).thenReturn(Optional.of(user(5, "ADMIN", "ACTIVE")));
        when(users.find(6L)).thenReturn(Optional.of(user(6, "SUPER_ADMIN", "LOCKED")));

        for (long actor : new long[] {5, 6, 99}) {
            assertThatThrownBy(() -> service.accounts(actor, LocalDate.of(2026, 9, 1), "Asia/Ho_Chi_Minh"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> service.ownerNames(actor, List.of(1L)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        verifyNoInteractions(stats);
    }

    @Test
    void ownerNamesAreCapped() {
        List<Long> ids = LongStream.rangeClosed(1, PlatformStatsService.MAX_OWNER_NAMES + 1).boxed().toList();

        assertThatThrownBy(() -> service.ownerNames(ACTOR, ids))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static UserRecord user(long id, String role, String status) {
        return new UserRecord(id, "u" + id + "@fcourt.vn", "User " + id, null, role, status,
                OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }
}
