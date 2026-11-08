package com.fcourt.voucher;

import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class VoucherApplyService {
    private final VoucherRepository vouchers;
    private final VoucherRedemptions applications;
    private final VoucherBookingClient booking;
    private final TransactionTemplate tx;
    private final Clock clock;
    VoucherApplyService(VoucherRepository vouchers, VoucherRedemptions applications, VoucherBookingClient booking,
            TransactionTemplate tx, Clock clock) {
        this.vouchers = vouchers; this.applications = applications; this.booking = booking; this.tx = tx; this.clock = clock;
    }

    VoucherRedemption apply(VoucherApplyRequest request, Jwt jwt) {
        long customer = customer(jwt);
        var order = booking.owned(request.bookingId(), jwt);
        if (order.gross().compareTo(request.totalAmount()) != 0)
            throw bad("Tổng tiền không khớp với giá gốc của đơn đặt sân");
        VoucherRedemption application;
        try { application = tx.execute(ignored -> reserve(request, order, customer)); }
        catch (DuplicateKeyException error) { throw bad("Đơn đặt sân hoặc khách hàng đã có voucher đang áp dụng"); }
        try { booking.attach(application, jwt); }
        catch (ResponseStatusException error) {
            // Keep unknown outcomes reserved. A lost response may hide a committed attachment;
            // retrying the same application is safe, while releasing it could oversell quota.
            if (error.getStatusCode().is4xxClientError()) tx.executeWithoutResult(ignored -> {
                applications.lockVoucher(application.voucherId()); applications.release(application.bookingId());
            });
            throw error;
        }
        return application;
    }

    private VoucherRedemption reserve(VoucherApplyRequest request, VoucherBookingClient.Snapshot order, long customer) {
        var voucher = vouchers.lockByCode(request.code()).orElseThrow(() -> bad("Mã khuyến mãi không tồn tại"));
        var existing = applications.booking(order.id(), true);
        if (existing.isPresent()) {
            var previous = existing.get();
            if (previous.customerId() != customer || previous.voucherId() != voucher.id() || "RELEASED".equals(previous.status()))
                throw bad("Đơn đặt sân đã áp dụng voucher khác hoặc lượt áp dụng đã kết thúc");
            if (previous.originalAmount().compareTo(order.gross()) != 0) throw bad("Giá gốc của đơn đặt sân đã thay đổi");
            return previous;
        }
        if (!"PENDING_PAYMENT".equals(order.status()) || order.paymentFrozen()) throw bad("Đơn đặt sân đã khóa thanh toán");
        if (order.holdExpiresAt() == null || !order.holdExpiresAt().isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.GONE, "Đơn đặt sân đã hết thời gian giữ chỗ");
        if (voucher.branchId() != order.branchId()) throw bad("Mã khuyến mãi không áp dụng cho chi nhánh này");
        var now = clock.instant();
        if (voucher.status() != Voucher.Status.ACTIVE || !now.isBefore(voucher.endDate())) throw bad("Mã khuyến mãi đã hết hạn");
        if (now.isBefore(voucher.startDate())) throw bad("Mã khuyến mãi chưa đến thời gian sử dụng");
        if (voucher.usedCount() + applications.reserved(voucher.id()) >= voucher.usageLimit()) throw bad("Mã khuyến mãi đã hết lượt sử dụng");
        if (order.gross().compareTo(voucher.minOrderAmount()) < 0)
            throw bad("Đơn hàng chưa đạt số tiền tối thiểu " + voucher.minOrderAmount().toPlainString());
        if (applications.alreadyUsed(voucher.id(), customer)) throw bad("Bạn đã sử dụng mã khuyến mãi này hoặc đang áp dụng cho đơn khác");
        var quote = VoucherPricing.calculate(voucher, order.branchId(), order.gross(), now);
        var application = new VoucherRedemption(UUID.randomUUID(), voucher.id(), order.id(), customer, order.branchId(),
                order.gross(), quote.discountAmount(), quote.finalAmount(), order.holdExpiresAt(), "RESERVED", null);
        applications.insert(application);
        return application;
    }
    private long customer(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Object claim = jwt.getClaims().get("roles");
        var roles = claim instanceof Collection<?> values ? values : List.of(Objects.toString(jwt.getClaims().get("role"), ""));
        if (!roles.contains("CUSTOMER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ khách hàng được áp dụng voucher");
        try { long id = Long.parseLong(jwt.getSubject()); if (id > 0) return id; }
        catch (NumberFormatException ignored) {}
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
