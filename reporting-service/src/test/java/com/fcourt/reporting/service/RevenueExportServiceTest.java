package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReportQueryStore;
import com.fcourt.reporting.persistence.ReportQueryStore.RevenueRow;
import com.fcourt.reporting.service.RevenueExportService.Format;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RevenueExportServiceTest {
    static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 10, 31);

    FacilityClient facility;
    ReportQueryStore store;
    RevenueExportService service;

    @BeforeEach
    void setUp() {
        facility = mock(FacilityClient.class);
        store = mock(ReportQueryStore.class);
        service = new RevenueExportService(facility, store, Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Ho_Chi_Minh");
    }

    static OwnedBranch branch(long id) {
        return new OwnedBranch(id, 7L, "ACTIVE", LocalTime.of(6, 0), LocalTime.of(22, 0), 2);
    }

    @Test
    void exportsOnlyTheCallersBranchesInTheLocalDayRange() throws Exception {
        when(facility.ownedBranches(7, "tok")).thenReturn(List.of(branch(9), branch(3)));
        doAnswer(call -> {
            Consumer<RevenueRow> sink = call.getArgument(3);
            sink.accept(new RevenueRow(11, 3, LocalDate.of(2026, 10, 5), Instant.parse("2026-10-05T11:30:00Z"), 2,
                    new BigDecimal("300000.00"), "CONFIRMED"));
            return null;
        }).when(store).revenueRows(anyCollection(), any(), any(), any());

        var file = service.revenue(7, "tok", null, FROM, TO, Format.XLSX);

        verify(store).revenueRows(eq(List.of(3L, 9L)), eq(FROM), eq(TO), any());
        assertThat(file.filename()).isEqualTo("bao-cao-doanh-thu_20261001_20261031.xlsx");
        assertThat(file.mediaType()).contains("spreadsheetml");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getSheetAt(0).getRow(8).getCell(5).getNumericCellValue()).isEqualTo(300000.0);
        }
    }

    @Test
    void singleBranchGoesThroughTheOwnershipCheck() {
        when(facility.ownedBranch(3, 7, "tok")).thenReturn(branch(3));

        var file = service.revenue(7, "tok", 3L, FROM, TO, Format.CSV);

        assertThat(file.filename()).endsWith(".csv");
        verify(facility, never()).ownedBranches(7, "tok");
    }

    @Test
    void foreignBranchIsDeniedBeforeAnyDataIsRead() {
        when(facility.ownedBranch(99, 7, "tok")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.revenue(7, "tok", 99L, FROM, TO, Format.XLSX))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(store);
    }

    @Test
    void ownerWithoutBranchesGetsAnEmptyReportWithoutQuerying() {
        when(facility.ownedBranches(7, "tok")).thenReturn(List.of());

        var file = service.revenue(7, "tok", null, FROM, TO, Format.CSV);

        assertThat(new String(file.content(), java.nio.charset.StandardCharsets.UTF_8)).contains("Doanh thu ròng (0 đơn)");
        verifyNoInteractions(store);
    }

    @Test
    void invalidRangesAreBadRequests() {
        assertThatThrownBy(() -> service.revenue(7, "tok", null, TO, FROM, Format.XLSX))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.revenue(7, "tok", null, FROM, FROM.plusDays(366), Format.XLSX))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(facility, store);
    }

    @Test
    void maximumRangeIsAccepted() {
        when(facility.ownedBranches(7, "tok")).thenReturn(List.of());
        assertThat(service.revenue(7, "tok", null, FROM, FROM.plusDays(365), Format.CSV)).isNotNull();
    }

    @Test
    void formatMustBeXlsxOrCsv() {
        assertThat(Format.parse("XLSX")).isEqualTo(Format.XLSX);
        assertThat(Format.parse("csv")).isEqualTo(Format.CSV);
        assertThatThrownBy(() -> Format.parse("pdf")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> Format.parse(null)).isInstanceOf(ResponseStatusException.class);
    }
}
