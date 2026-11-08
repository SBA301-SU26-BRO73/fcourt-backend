package com.fcourt.auth.superadmin;

import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.superadmin.PlatformStatsRepository.OwnerName;
import com.fcourt.auth.user.UserRole;
import com.fcourt.auth.user.UserStatus;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account figures for the SUPER_ADMIN platform overview; the actor is re-checked against the database. */
@Service
public class PlatformStatsService {
    static final int MAX_OWNER_NAMES = 20;

    public record OwnerCounts(long active, long pending, long rejected, long inactive, long total) {
    }

    public record CustomerCounts(long total, long active) {
    }

    public record MonthCount(String month, long count) {
    }

    public record AccountStats(OwnerCounts owners, CustomerCounts customers, List<MonthCount> newAccountsByMonth) {
    }

    private final PlatformStatsRepository stats;
    private final SuperAdminRepository users;

    PlatformStatsService(PlatformStatsRepository stats, SuperAdminRepository users) {
        this.stats = stats;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public AccountStats accounts(long actorId, LocalDate from, String timeZone) {
        requireActiveSuperAdmin(actorId);
        ZoneId zone = zone(timeZone);
        Map<String, Long> owners = stats.countByStatus(UserRole.ADMIN.name());
        Map<String, Long> customers = stats.countByStatus(UserRole.CUSTOMER.name());
        long ownerTotal = owners.values().stream().mapToLong(Long::longValue).sum();
        long customerTotal = customers.values().stream().mapToLong(Long::longValue).sum();
        var months = stats.newAccountsByMonth(from.atStartOfDay(zone).toInstant(), zone.getId()).entrySet().stream()
                .map(entry -> new MonthCount(entry.getKey(), entry.getValue())).toList();
        return new AccountStats(
                new OwnerCounts(owners.getOrDefault(UserStatus.ACTIVE.name(), 0L),
                        owners.getOrDefault(UserStatus.PENDING_APPROVAL.name(), 0L),
                        owners.getOrDefault(UserStatus.REJECTED.name(), 0L),
                        owners.getOrDefault(UserStatus.INACTIVE.name(), 0L), ownerTotal),
                new CustomerCounts(customerTotal, customers.getOrDefault(UserStatus.ACTIVE.name(), 0L)), months);
    }

    @Transactional(readOnly = true)
    public List<OwnerName> ownerNames(long actorId, List<Long> ids) {
        requireActiveSuperAdmin(actorId);
        if (ids.size() > MAX_OWNER_NAMES) {
            throw ApiException.badRequest("At most " + MAX_OWNER_NAMES + " ids are allowed");
        }
        return stats.ownerNames(ids);
    }

    private static ZoneId zone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException invalid) {
            throw ApiException.badRequest("Invalid timeZone");
        }
    }

    private void requireActiveSuperAdmin(long actorId) {
        boolean active = users.find(actorId)
                .filter(actor -> UserRole.SUPER_ADMIN.name().equals(actor.role()))
                .filter(actor -> UserStatus.ACTIVE.name().equals(actor.status()))
                .isPresent();
        if (!active) {
            throw ApiException.forbidden("Super admin account is not active");
        }
    }
}
