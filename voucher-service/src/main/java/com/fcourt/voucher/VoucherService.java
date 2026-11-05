package com.fcourt.voucher;

import java.time.Clock;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class VoucherService {
    private final VoucherRepository repository;
    private final VoucherAuthority authority;
    private final Clock clock;
    VoucherService(VoucherRepository repository, VoucherAuthority authority, Clock clock) {
        this.repository = repository;
        this.authority = authority;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Voucher.Page list(Jwt jwt, Long branchId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || (branchId != null && branchId <= 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination or branch ID");
        var branches = authority.branches(jwt);
        if (branchId != null) { requireBranch(branches, branchId); branches = List.of(branchId); }
        var result = repository.page(branches, page, size);
        var now = clock.instant();
        return new Voucher.Page(result.items().stream().map(voucher -> voucher.at(now)).toList(), page, size, result.total());
    }

    @Transactional(readOnly = true)
    public Voucher get(Jwt jwt, long id) {
        requireId(id);
        return repository.find(id, authority.branches(jwt), false).orElseThrow(VoucherService::missing).at(clock.instant());
    }

    @Transactional
    public Voucher create(Jwt jwt, VoucherRequest request) {
        requireBranch(authority.branches(jwt), request.branchId());
        try { return repository.create(request).at(clock.instant()); }
        catch (DuplicateKeyException error) { throw duplicate(); }
    }

    @Transactional
    public Voucher update(Jwt jwt, long id, VoucherRequest request) {
        requireId(id);
        var branches = authority.branches(jwt);
        var current = repository.find(id, branches, true).orElseThrow(VoucherService::missing);
        requireBranch(branches, request.branchId());
        if (request.usageLimit() < current.usedCount())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usageLimit must not be below usedCount");
        try { return repository.update(id, request).at(clock.instant()); }
        catch (DuplicateKeyException error) { throw duplicate(); }
    }

    @Transactional
    public void delete(Jwt jwt, long id) {
        requireId(id);
        repository.find(id, authority.branches(jwt), true).orElseThrow(VoucherService::missing);
        repository.delete(id);
    }

    private static void requireId(long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid voucher ID");
    }
    private static void requireBranch(List<Long> branches, long id) {
        if (!branches.contains(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch access denied");
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found"); }
    private static ResponseStatusException duplicate() { return new ResponseStatusException(HttpStatus.CONFLICT, "Voucher code already exists"); }
}
