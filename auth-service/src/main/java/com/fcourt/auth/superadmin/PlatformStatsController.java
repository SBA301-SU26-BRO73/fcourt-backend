package com.fcourt.auth.superadmin;

import com.fcourt.auth.superadmin.PlatformStatsRepository.OwnerName;
import com.fcourt.auth.superadmin.PlatformStatsService.AccountStats;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Aggregates consumed by reporting-service for the SUPER_ADMIN overview; SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/super-admin/stats")
class PlatformStatsController {
    private final PlatformStatsService service;

    PlatformStatsController(PlatformStatsService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    AccountStats accounts(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(defaultValue = "Asia/Ho_Chi_Minh") String timeZone) {
        return service.accounts(Long.parseLong(jwt.getSubject()), from, timeZone);
    }

    @GetMapping("/owner-names")
    List<OwnerName> ownerNames(@AuthenticationPrincipal Jwt jwt, @RequestParam List<Long> ids) {
        return service.ownerNames(Long.parseLong(jwt.getSubject()), ids);
    }
}
