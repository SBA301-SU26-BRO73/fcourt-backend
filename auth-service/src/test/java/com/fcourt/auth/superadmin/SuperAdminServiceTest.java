package com.fcourt.auth.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.superadmin.SuperAdminDtos.DocumentResponse;
import com.fcourt.auth.superadmin.SuperAdminRepository.UserRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SuperAdminServiceTest {
    static final long ACTOR = 16;
    static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    SuperAdminRepository users;
    OutboxWriter outbox;
    SuperAdminService service;

    @BeforeEach
    void setUp() {
        users = mock(SuperAdminRepository.class);
        outbox = mock(OutboxWriter.class);
        service = new SuperAdminService(users, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
        when(users.find(ACTOR)).thenReturn(Optional.of(user(ACTOR, "SUPER_ADMIN", "ACTIVE")));
        when(users.documentsOf(any())).thenReturn(Map.of());
    }

    @Test
    void pendingOwnersComeWithTheirDocuments() {
        when(users.findPendingOwners()).thenReturn(List.of(user(17, "ADMIN", "PENDING_APPROVAL")));
        when(users.documentsOf(List.of(17L))).thenReturn(
                Map.of(17L, List.of(new DocumentResponse(1, "https://x/licence.jpg", "LEGAL"))));

        var pending = service.pendingCourtAdmins(ACTOR);

        assertThat(pending).singleElement().satisfies(owner -> {
            assertThat(owner.status()).isEqualTo("PENDING_APPROVAL");
            assertThat(owner.documents()).extracting(DocumentResponse::url).containsExactly("https://x/licence.jpg");
        });
    }

    @Test
    void approveActivatesOwnerAuditsAndEmitsEvent() {
        when(users.findForUpdate(17)).thenReturn(Optional.of(user(17, "ADMIN", "PENDING_APPROVAL")));
        when(users.find(17)).thenReturn(Optional.of(user(17, "ADMIN", "ACTIVE")));

        var approved = service.approve(ACTOR, 17, "  documents verified ");

        assertThat(approved.status()).isEqualTo("ACTIVE");
        verify(users).updateStatus(17, "ACTIVE", NOW);
        verify(users).logAction(17, ACTOR, "APPROVE", "PENDING_APPROVAL", "ACTIVE", "documents verified", NOW);
        verify(outbox).append(17, "OwnerApprovedEvent", 1, new OwnerApprovedEvent(17, ACTOR, NOW));
    }

    @Test
    void approvingNonPendingOwnerConflictsWithoutSideEffects() {
        when(users.findForUpdate(1)).thenReturn(Optional.of(user(1, "ADMIN", "ACTIVE")));

        assertThatThrownBy(() -> service.approve(ACTOR, 1, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).updateStatus(anyLong(), anyString(), any());
        verify(outbox, never()).append(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void approvingCustomerIsNotFound() {
        when(users.findForUpdate(6)).thenReturn(Optional.of(user(6, "CUSTOMER", "PENDING_APPROVAL")));

        assertThatThrownBy(() -> service.approve(ACTOR, 6, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void inactiveSuperAdminIsForbidden() {
        when(users.find(ACTOR)).thenReturn(Optional.of(user(ACTOR, "SUPER_ADMIN", "LOCKED")));

        assertThatThrownBy(() -> service.pendingCourtAdmins(ACTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void tokenSubjectThatIsNotASuperAdminIsForbidden() {
        when(users.find(6)).thenReturn(Optional.of(user(6, "CUSTOMER", "ACTIVE")));

        assertThatThrownBy(() -> service.approve(6, 17, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectRequiresReasonAndAuditsIt() {
        assertThatThrownBy(() -> service.reject(ACTOR, 18, "   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        when(users.findForUpdate(18)).thenReturn(Optional.of(user(18, "ADMIN", "PENDING_APPROVAL")));
        when(users.find(18)).thenReturn(Optional.of(user(18, "ADMIN", "REJECTED")));

        assertThat(service.reject(ACTOR, 18, "Blurry licence").status()).isEqualTo("REJECTED");
        verify(users).logAction(18, ACTOR, "REJECT", "PENDING_APPROVAL", "REJECTED", "Blurry licence", NOW);
        verify(outbox, never()).append(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void toggleLocksActiveAndUnlocksLocked() {
        when(users.findForUpdate(6)).thenReturn(Optional.of(user(6, "CUSTOMER", "ACTIVE")));
        assertThat(service.toggleStatus(ACTOR, 6, "spam").status()).isEqualTo("LOCKED");
        verify(users).logAction(6, ACTOR, "LOCK", "ACTIVE", "LOCKED", "spam", NOW);

        when(users.findForUpdate(6)).thenReturn(Optional.of(user(6, "CUSTOMER", "LOCKED")));
        assertThat(service.toggleStatus(ACTOR, 6, null).status()).isEqualTo("ACTIVE");
        verify(users).logAction(6, ACTOR, "UNLOCK", "LOCKED", "ACTIVE", null, NOW);
    }

    @Test
    void toggleRefusesSuperAdminsAndNonToggleableStatuses() {
        when(users.findForUpdate(ACTOR)).thenReturn(Optional.of(user(ACTOR, "SUPER_ADMIN", "ACTIVE")));
        when(users.findForUpdate(17)).thenReturn(Optional.of(user(17, "ADMIN", "PENDING_APPROVAL")));

        assertThatThrownBy(() -> service.toggleStatus(ACTOR, ACTOR, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.toggleStatus(ACTOR, 17, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).updateStatus(anyLong(), anyString(), any());
    }

    @Test
    void unknownUserIsNotFound() {
        when(users.findForUpdate(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleStatus(ACTOR, 999, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(users, never()).logAction(eq(999L), anyLong(), anyString(), any(), anyString(), any(), any());
    }

    static UserRecord user(long id, String role, String status) {
        return new UserRecord(id, "user" + id + "@demo.com", "User " + id, "09000000" + id, role, status,
                OffsetDateTime.parse("2026-10-20T08:00:00Z"));
    }
}
