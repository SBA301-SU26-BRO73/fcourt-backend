package com.fcourt.subscription.courttype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeRequest;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class CourtTypeServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    CourtTypeRepository courtTypes;
    CourtTypeService service;

    @BeforeEach
    void setUp() {
        courtTypes = mock(CourtTypeRepository.class);
        service = new CourtTypeService(courtTypes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listPassesTheActiveFilterAndPagingAndReportsTotals() {
        when(courtTypes.findPage(true, 5, 10)).thenReturn(List.of(courtType(1, "Football", true)));
        when(courtTypes.count(true)).thenReturn(11L);

        var page = service.list(true, Pagination.of(2, 5));

        assertThat(page.content()).extracting(CourtTypeResponse::name).containsExactly("Football");
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.totalElements()).isEqualTo(11);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void listWithoutFilterAsksForBothActiveAndInactive() {
        service.list(null, Pagination.of(null, null));

        verify(courtTypes).findPage(null, 10, 0);
        verify(courtTypes).count(null);
    }

    @Test
    void getReturnsTheTypeOrNotFound() {
        when(courtTypes.find(1)).thenReturn(Optional.of(courtType(1, "Football", true)));
        when(courtTypes.find(2)).thenReturn(Optional.empty());

        assertThat(service.get(1).name()).isEqualTo("Football");
        assertThatThrownBy(() -> service.get(2)).satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND,
                "Court type not found"));
    }

    @Test
    void createTrimsFieldsStoresBlankOptionalsAsNullAndDefaultsToActive() {
        when(courtTypes.insert(anyString(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(7L);
        when(courtTypes.find(7)).thenReturn(Optional.of(courtType(7, "Football", true)));

        var created = service.create(new CourtTypeRequest("  Football ", " ", " Pitch ", "", "#00ff00", null));

        assertThat(created.id()).isEqualTo(7);
        verify(courtTypes).insert("Football", null, "Pitch", null, "#00ff00", true, NOW);
    }

    @Test
    void createHonoursAnExplicitInactiveFlag() {
        when(courtTypes.insert(anyString(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(7L);
        when(courtTypes.find(7)).thenReturn(Optional.of(courtType(7, "Padel", false)));

        service.create(new CourtTypeRequest("Padel", null, null, null, null, false));

        verify(courtTypes).insert("Padel", null, null, null, null, false, NOW);
    }

    @Test
    void createRejectsANameAlreadyInUse() {
        when(courtTypes.nameInUse("Football", 0)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Football")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Court type name already exists"));
        verify(courtTypes, never()).insert(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void createTurnsALostRaceOnTheUniqueIndexIntoAConflict() {
        when(courtTypes.insert(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new DuplicateKeyException("uq_court_types_name_active"));

        assertThatThrownBy(() -> service.create(request("Football")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Court type name already exists"));
    }

    @Test
    void updateReplacesTheFieldsAndMayKeepItsOwnName() {
        when(courtTypes.find(1)).thenReturn(Optional.of(courtType(1, "Football", true)));

        service.update(1, new CourtTypeRequest("Football", "Soccer", null, null, null, false));

        verify(courtTypes).nameInUse("Football", 1);
        verify(courtTypes).update(1, "Football", "Soccer", null, null, null, false, NOW);
    }

    @Test
    void updateWithoutTheActiveFlagKeepsTheCurrentValueInsteadOfReEnablingTheType() {
        when(courtTypes.find(1)).thenReturn(Optional.of(courtType(1, "Volleyball", false)));

        service.update(1, request("Volleyball"));

        verify(courtTypes).update(1, "Volleyball", null, null, null, null, false, NOW);
    }

    @Test
    void updateRejectsANameTakenByAnotherType() {
        when(courtTypes.find(1)).thenReturn(Optional.of(courtType(1, "Football", true)));
        when(courtTypes.nameInUse("Tennis", 1)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1, request("Tennis")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Court type name already exists"));
        verify(courtTypes, never()).update(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void updateOfAMissingTypeIsNotFound() {
        when(courtTypes.find(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9, request("Tennis")))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Court type not found"));
    }

    @Test
    void deleteIsASoftDeleteStampedWithTheClock() {
        when(courtTypes.find(1)).thenReturn(Optional.of(courtType(1, "Football", true)));

        service.delete(1);

        verify(courtTypes).softDelete(eq(1L), eq(NOW));
    }

    @Test
    void deleteOfAMissingTypeIsNotFoundAndWritesNothing() {
        when(courtTypes.find(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Court type not found"));
        verify(courtTypes, never()).softDelete(anyLong(), any());
    }

    static void assertApi(Throwable error, HttpStatus status, String detail) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.status()).isEqualTo(status);
            assertThat(api.getMessage()).isEqualTo(detail);
        });
    }

    static CourtTypeRequest request(String name) {
        return new CourtTypeRequest(name, null, null, null, null, null);
    }

    static CourtTypeResponse courtType(long id, String name, boolean active) {
        return new CourtTypeResponse(id, name, null, null, null, null, active,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
