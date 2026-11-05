package com.fcourt.reporting.service;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReportQueryStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner revenue export (SCRUM-76). Scope is resolved exactly like the dashboard: facility-service decides
 * which branches belong to the caller, then only those ids are read from the local read model.
 */
@Service
public class RevenueExportService {
    public static final int MAX_DAYS = 366;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.BASIC_ISO_DATE;

    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        CSV("csv", "text/csv; charset=UTF-8");

        final String extension;
        final String mediaType;

        Format(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        public String extension() { return extension; }

        public String mediaType() { return mediaType; }

        public static Format parse(String value) {
            if (value != null) {
                String normalized = value.toLowerCase(Locale.ROOT);
                for (Format format : values()) {
                    if (format.extension.equals(normalized)) return format;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be xlsx or csv");
        }
    }

    public record ExportFile(String filename, String mediaType, byte[] content) { }

    private final FacilityClient facility;
    private final ReportQueryStore store;
    private final Clock clock;
    private final ZoneId zone;

    public RevenueExportService(FacilityClient facility, ReportQueryStore store, Clock clock,
                                @Value("${reporting.time-zone}") String zone) {
        this.facility = facility;
        this.store = store;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public ExportFile revenue(long adminId, String bearer, Long branchId, LocalDate from, LocalDate to, Format format) {
        validate(from, to);
        List<OwnedBranch> branches = branchId == null ? facility.ownedBranches(adminId, bearer)
                : List.of(facility.ownedBranch(branchId, adminId, bearer));
        List<Long> ids = branches.stream().map(OwnedBranch::id).sorted().toList();
        var header = new RevenueReport.Header(adminId, ids, from, to, zone, clock.instant());
        try (var sink = format == Format.XLSX ? RevenueReport.xlsx(header) : RevenueReport.csv(header)) {
            if (!ids.isEmpty()) {
                store.revenueRows(ids, from, to, sink);
            }
            String name = "bao-cao-doanh-thu_" + STAMP.format(from) + "_" + STAMP.format(to) + "." + format.extension;
            return new ExportFile(name, format.mediaType, sink.finish());
        }
    }

    static void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromDate must not be after toDate");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Date range must not exceed " + MAX_DAYS + " days");
        }
    }
}
