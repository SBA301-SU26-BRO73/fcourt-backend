package com.fcourt.payment;

import org.springframework.stereotype.Component;

/**
 * Turns the stored object keys of receipts and refund proofs into short-lived presigned URLs on the way out of an
 * API. Keys stay in the database and in events; only authorized responses carry a URL.
 */
@Component
class ReceiptUrls {
    private final ReceiptStorage storage;

    ReceiptUrls(ReceiptStorage storage) { this.storage = storage; }

    String view(String stored) { return storage.signedUrl(stored); }

    PaymentRecord view(PaymentRecord p) {
        if (p == null || p.receiptImageUrl() == null) return p;
        return new PaymentRecord(p.id(), p.bookingId(), p.branchId(), p.amount(), p.status(), view(p.receiptImageUrl()),
                p.submittedAt(), p.rejectionReason(), p.checkout(), p.checkInCode(), p.qrImageUrl(), p.customerId(), p.confirmedAt());
    }

    RefundView view(RefundView r) {
        if (r == null || r.transferProofUrl() == null) return r;
        return new RefundView(r.id(), r.bookingId(), r.amount(), r.status(), r.bankName(), r.bankBin(), r.bankAccountNumber(),
                r.bankAccountName(), r.reason(), r.adminNote(), view(r.transferProofUrl()), r.createdAt(), r.updatedAt(), r.customerPhone());
    }

    CustomerPayments.Page view(CustomerPayments.Page page) {
        return new CustomerPayments.Page(page.items().stream().map(t -> new CustomerPayments.Transaction(t.id(), t.transactionCode(),
                t.bookingId(), t.bookingCode(), t.branchName(), t.courtName(), t.amount(), t.method(), t.status(), t.occurredAt(),
                view(t.receiptImageUrl()), t.customerName(), t.customerPhone())).toList(), page.page(), page.size(), page.total());
    }

    UnifiedLedger.Page view(UnifiedLedger.Page page) {
        return new UnifiedLedger.Page(page.items().stream().map(e -> new UnifiedLedger.Entry(e.id(), e.transactionKey(),
                e.transactionType(), e.transactionCode(), e.bookingId(), e.bookingCode(), e.branchName(), e.courtName(), e.amount(),
                e.method(), e.status(), e.occurredAt(), view(e.receiptImageUrl()))).toList(), page.page(), page.size(), page.total(), page.summary());
    }
}
