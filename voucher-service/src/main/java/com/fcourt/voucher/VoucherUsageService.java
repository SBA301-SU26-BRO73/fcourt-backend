package com.fcourt.voucher;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class VoucherUsageService {
    private final VoucherUsageRepository history;
    private final VoucherAuthority authority;
    VoucherUsageService(VoucherUsageRepository history, VoucherAuthority authority) { this.history = history; this.authority = authority; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VoucherUsage.Page list(long id, Jwt jwt, int page, int size) {
        if (id <= 0 || page < 0 || page > 100000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số lịch sử sử dụng voucher không hợp lệ");
        var branches = authority.branches(jwt);
        if (!history.visible(id, branches)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy voucher trong phạm vi quản lý");
        return history.page(id, branches, page, size);
    }
}
