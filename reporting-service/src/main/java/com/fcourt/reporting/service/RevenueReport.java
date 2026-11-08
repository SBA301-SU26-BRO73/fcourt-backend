package com.fcourt.reporting.service;

import com.fcourt.reporting.persistence.ReportQueryStore.RevenueRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Revenue report layout shared by .xlsx and .csv: branch/period header block, one line per booking
 * (order id, play date, recognized-at, slots, amount, status) and a final net revenue line.
 * Money is the same recognized {@code revenue} the owner dashboard sums, so totals match it exactly.
 */
public final class RevenueReport {
    public static final String[] COLUMNS = {"Mã đơn", "Sân", "Khách hàng", "Chi nhánh", "Ngày chơi",
            "Ngày ghi nhận doanh thu", "Số slot", "Số tiền (VND)", "Trạng thái"};
    static final String TITLE = "BÁO CÁO DOANH THU";
    static final String NET_LABEL = "Doanh thu ròng";
    static final String MONEY_FORMAT = "#,##0";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RevenueReport() { }

    public record Header(long ownerId, List<Long> branchIds, LocalDate from, LocalDate to, ZoneId zone,
                         java.time.Instant generatedAt) {
        String branches() {
            return branchIds.stream().map(id -> "#" + id).collect(Collectors.joining(", "));
        }

        String period() {
            return DATE.format(from) + " → " + DATE.format(to);
        }

        String generated() {
            return DATE_TIME.format(generatedAt.atZone(zone)) + " (" + zone.getId() + ")";
        }
    }

    /** Receives rows in order, then {@link #finish(OutputStream)} writes the summary and artifact. */
    public interface Sink extends Consumer<RevenueRow>, AutoCloseable {
        void finish(OutputStream output);

        @Override
        void close();
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case "CONFIRMED" -> "Đã xác nhận";
            case "PENDING" -> "Chờ xác nhận";
            case "CANCELLED" -> "Đã hủy";
            default -> status;
        };
    }

    public static Sink xlsx(Header header) {
        return new XlsxSink(header);
    }

    public static Sink csv(Header header, OutputStream output) {
        return new CsvSink(header, output);
    }

    private static final class XlsxSink implements Sink {
        private final Header header;
        private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        private final Sheet sheet = workbook.createSheet("Doanh thu");
        private final CellStyle money;
        private final CellStyle bold;
        private final CellStyle boldMoney;
        private final CellStyle date;
        private int rowIndex;
        private BigDecimal net = BigDecimal.ZERO;
        private long count;

        XlsxSink(Header header) {
            this.header = header;
            DataFormat format = workbook.createDataFormat();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            money = workbook.createCellStyle();
            money.setDataFormat(format.getFormat(MONEY_FORMAT));
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            boldMoney = workbook.createCellStyle();
            boldMoney.setFont(boldFont);
            boldMoney.setDataFormat(format.getFormat(MONEY_FORMAT));
            boldMoney.setBorderTop(BorderStyle.THIN);
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));

            put(TITLE, null, bold);
            put("Chủ sân (ID)", String.valueOf(header.ownerId()), null);
            put("Chi nhánh", header.branches(), null);
            put("Kỳ báo cáo", header.period(), null);
            put("Ngày xuất", header.generated(), null);
            rowIndex++;
            Row columns = sheet.createRow(rowIndex++);
            CellStyle head = workbook.createCellStyle();
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int i = 0; i < COLUMNS.length; i++) {
                var cell = columns.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(head);
            }
        }

        private void put(String label, String value, CellStyle style) {
            Row row = sheet.createRow(rowIndex++);
            var cell = row.createCell(0);
            cell.setCellValue(label);
            if (style != null) cell.setCellStyle(style);
            if (value != null) row.createCell(1).setCellValue(value);
        }

        @Override
        public void accept(RevenueRow value) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(value.bookingId());
            row.createCell(1).setCellValue(value.courtName() == null ? "" : value.courtName());
            row.createCell(2).setCellValue(value.customerName() == null ? "" : value.customerName());
            row.createCell(3).setCellValue("#" + value.branchId());
            var played = row.createCell(4);
            if (value.bookingDate() != null) {
                played.setCellValue(value.bookingDate());
                played.setCellStyle(date);
            }
            if (value.revenueAt() != null) row.createCell(5).setCellValue(DATE_TIME.format(value.revenueAt().atZone(header.zone())));
            row.createCell(6).setCellValue(value.slotCount());
            var amount = row.createCell(7);
            amount.setCellValue(value.revenue().doubleValue());
            amount.setCellStyle(money);
            row.createCell(8).setCellValue(statusLabel(value.status()));
            net = net.add(value.revenue());
            count++;
        }

        @Override
        public void finish(OutputStream output) {
            Row total = sheet.createRow(rowIndex++);
            var label = total.createCell(0);
            label.setCellValue(NET_LABEL + " (" + count + " đơn)");
            label.setCellStyle(bold);
            var amount = total.createCell(7);
            amount.setCellValue(net.doubleValue());
            amount.setCellStyle(boldMoney);
            // Widths are fixed: auto-sizing a streaming sheet would need every row kept in memory.
            int[] widths = {14, 22, 24, 14, 14, 26, 10, 18, 16};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            try {
                workbook.write(output);
                output.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public void close() {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    private static final class CsvSink implements Sink {
        private static final String EOL = "\r\n";
        private final Header header;
        private final BufferedWriter out;
        private BigDecimal net = BigDecimal.ZERO;
        private long count;

        CsvSink(Header header, OutputStream output) {
            this.header = header;
            out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writeBom();
            line(TITLE);
            line("Chủ sân (ID)", String.valueOf(header.ownerId()));
            line("Chi nhánh", header.branches());
            line("Kỳ báo cáo", header.period());
            line("Ngày xuất", header.generated());
            line("");
            line(COLUMNS);
        }

        private void writeBom() {
            try {
                out.write('\uFEFF'); // BOM so Excel reads UTF-8 (Vietnamese)
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private void line(String... cells) {
            try {
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) out.write(',');
                    out.write(escape(cells[i]));
                }
                out.write(EOL);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static String escape(String cell) {
            if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
                return cell;
            }
            return '"' + cell.replace("\"", "\"\"") + '"';
        }

        @Override
        public void accept(RevenueRow value) {
            line(String.valueOf(value.bookingId()), value.courtName() == null ? "" : value.courtName(),
                    value.customerName() == null ? "" : value.customerName(), "#" + value.branchId(),
                    value.bookingDate() == null ? "" : DATE.format(value.bookingDate()),
                    value.revenueAt() == null ? "" : DATE_TIME.format(value.revenueAt().atZone(header.zone())), String.valueOf(value.slotCount()),
                    value.revenue().toPlainString(), statusLabel(value.status()));
            net = net.add(value.revenue());
            count++;
        }

        @Override
        public void finish(OutputStream output) {
            line(NET_LABEL + " (" + count + " đơn)", "", "", "", "", "", "", net.toPlainString(), "");
            try {
                out.flush();
                output.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public void close() { }
    }
}
