package com.fcourt.reporting.api;

import com.fcourt.reporting.service.RevenueExportService;
import com.fcourt.reporting.service.RevenueExportService.ExportFile;
import com.fcourt.reporting.service.RevenueExportService.Format;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExportController {
    private final RevenueExportService export;

    ExportController(RevenueExportService export) {
        this.export = export;
    }

    /** Owner-scoped revenue report download; {@code branchId} omitted = every own branch. */
    @GetMapping("/api/admin/dashboard/export/revenue")
    ResponseEntity<StreamingResponseBody> revenue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) @Positive Long branchId) {
        ExportFile file = export.revenue(Long.parseLong(jwt.getSubject()), jwt.getTokenValue(), branchId,
                fromDate, toDate, Format.parse(format));
        StreamingResponseBody body = file.writer()::writeTo;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(body);
    }
}
