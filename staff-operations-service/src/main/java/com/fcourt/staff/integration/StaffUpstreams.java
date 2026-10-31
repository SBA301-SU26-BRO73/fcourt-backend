package com.fcourt.staff.integration;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Proposed IAM/facility HTTP boundary; integration requires the owning services. */
@Component
public class StaffUpstreams {
    private final RestClient auth;
    private final RestClient facility;

    public StaffUpstreams(RestClient.Builder builder,
                          @Value("${staff.auth.url}") String authUrl,
                          @Value("${staff.facility.url}") String facilityUrl) {
        auth = builder.clone().baseUrl(authUrl).build();
        facility = builder.clone().baseUrl(facilityUrl).build();
    }

    public void activeUser(long actor, String role, String token) {
        Account user = execute(() -> auth.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Account.class));
        if (user == null || user.id() == null || user.id() != actor) throw unavailable();
        if (!role.equals(user.role()) || !"ACTIVE".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active for this role");
        }
    }

    public void ownedBranch(long branchId, long adminId, String token) {
        Branch branch = execute(() -> facility.get().uri("/api/admin/branches/{id}", branchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Branch.class));
        validateBranch(branch, branchId);
        if (branch.adminId() == null || branch.adminId() <= 0) throw unavailable();
        if (!Objects.equals(branch.adminId(), adminId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch access denied");
        }
    }

    public void activeBranch(long branchId, String token) {
        Branch branch = execute(() -> facility.get().uri("/api/staff/branches/{id}", branchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Branch.class));
        validateBranch(branch, branchId);
    }

    private void validateBranch(Branch branch, long id) {
        if (branch == null || branch.id() == null || branch.id() != id) throw unavailable();
        if (!"ACTIVE".equals(branch.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch is not active");
        }
    }

    public Account create(CreateAccount request, String command, String token) {
        Account account = execute(() -> auth.post().uri("/api/internal/staff-accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", command).body(request).retrieve().body(Account.class));
        validateStaff(account);
        if (!request.email().equalsIgnoreCase(account.email())) throw unavailable();
        return account;
    }

    public Account update(long userId, UpdateAccount request, String command, String token) {
        Account account = execute(() -> auth.put().uri("/api/internal/staff-accounts/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", command).body(request).retrieve().body(Account.class));
        validateStaff(account);
        if (account.id() != userId) throw unavailable();
        return account;
    }

    public void disable(long userId, String command, String token) {
        Account account = execute(() -> auth.delete().uri("/api/internal/staff-accounts/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", command).retrieve().body(Account.class));
        if (account == null || !Objects.equals(account.id(), userId)
                || !"STAFF".equals(account.role()) || !"INACTIVE".equals(account.status())) throw unavailable();
    }

    private void validateStaff(Account account) {
        if (account == null || account.id() == null || account.id() <= 0 || !"STAFF".equals(account.role())
                || !"ACTIVE".equals(account.status()) || account.email() == null || account.fullName() == null
                || account.fullName().isBlank() || account.phone() == null) throw unavailable();
    }

    private <T> T execute(Call<T> operation) {
        try {
            return operation.run();
        } catch (HttpClientErrorException error) {
            int code = error.getStatusCode().value();
            if (code == 401 || code == 403 || code == 404 || code == 409) {
                throw new ResponseStatusException(error.getStatusCode(), "Upstream authorization or resource conflict");
            }
            throw unavailable();
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity or facility service unavailable");
    }

    @FunctionalInterface
    private interface Call<T> { T run(); }

    public record Account(Long id, String email, String fullName, String phone, String role, String status) { }
    public record Branch(Long id, Long adminId, String status) { }
    public record CreateAccount(long branchId, String email, String password, String fullName, String phone) {
        @Override public String toString() { return "CreateAccount[credentials=REDACTED]"; }
    }
    public record UpdateAccount(long branchId, String fullName, String phone) { }
}
