package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.reporting.persistence.ReportQueryStore.RevenueRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class RevenueReportTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final RevenueReport.Header HEADER = new RevenueReport.Header(7, List.of(3L, 9L),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), ZONE, Instant.parse("2026-11-02T03:00:00Z"));

    static RevenueRow row(long id, long branch, String status, String amount) {
        return new RevenueRow(id, branch, LocalDate.of(2026, 10, 5), Instant.parse("2026-10-05T11:30:00Z"), 2,
                new BigDecimal(amount), status);
    }

    static byte[] build(RevenueReport.Sink sink, RevenueRow... rows) {
        try (sink) {
            for (RevenueRow row : rows) sink.accept(row);
            return sink.finish();
        }
    }

    @Test
    void xlsxReopensWithHeaderBlockRowsAndNetTotal() throws IOException {
        byte[] bytes = build(RevenueReport.xlsx(HEADER), row(11, 3, "CONFIRMED", "250000.00"),
                row(12, 9, "CONFIRMED", "1234567.00"));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("BÁO CÁO DOANH THU");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("7");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("#3, #9");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).contains("2026-10-01").contains("2026-10-31");
            // Header at row 6, in Vietnamese with diacritics intact.
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Mã đơn");
            assertThat(sheet.getRow(6).getCell(5).getStringCellValue()).isEqualTo("Số tiền (VND)");
            var first = sheet.getRow(7);
            assertThat(first.getCell(0).getNumericCellValue()).isEqualTo(11);
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("2026-10-05 18:30"); // UTC+7
            assertThat(first.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(5).getNumericCellValue()).isEqualTo(250000.0);
            assertThat(first.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(first.getCell(6).getStringCellValue()).isEqualTo("Đã xác nhận");
            var total = sheet.getRow(9);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("Doanh thu ròng (2 đơn)");
            assertThat(total.getCell(5).getNumericCellValue()).isEqualTo(1484567.0);
        }
    }

    @Test
    void emptyPeriodStillProducesAValidWorkbookWithZeroNet() throws IOException {
        byte[] bytes = build(RevenueReport.xlsx(HEADER));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var total = workbook.getSheetAt(0).getRow(7);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("Doanh thu ròng (0 đơn)");
            assertThat(total.getCell(5).getNumericCellValue()).isZero();
        }
    }

    @Test
    void csvIsUtf8WithBomAndPlainIntegerMoney() {
        byte[] bytes = build(RevenueReport.csv(HEADER), row(11, 3, "CONFIRMED", "250000.00"),
                row(12, 9, "CANCELLED", "0.00"));

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = text.substring(1).lines().toList();
        assertThat(lines.get(0)).isEqualTo("BÁO CÁO DOANH THU");
        assertThat(lines.get(2)).isEqualTo("Chi nhánh,\"#3, #9\""); // embedded comma is quoted
        assertThat(lines.get(6)).isEqualTo("Mã đơn,Chi nhánh,Ngày chơi,Ngày ghi nhận doanh thu,Số slot,Số tiền (VND),Trạng thái");
        assertThat(lines.get(7)).isEqualTo("11,#3,2026-10-05,2026-10-05 18:30,2,250000.00,Đã xác nhận");
        assertThat(lines.get(8)).endsWith("Đã hủy");
        assertThat(lines.get(9)).isEqualTo("Doanh thu ròng (2 đơn),,,,,250000.00,");
    }
}
