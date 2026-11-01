package com.fcourt.pos.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PosViews {
    private PosViews() { }
    public record Product(long id, long branchId, String name, String unit, String category,
                          BigDecimal price, boolean active, int stockQuantity, String imageUrl) { }
    public record Item(long productId, String productName, String unit, int quantity,
                       BigDecimal priceAtOrder, BigDecimal lineTotal) { }
    public record Order(long id, String orderCode, long branchId, Long bookingId, long cashierUserId,
                        BigDecimal totalAmount, String paymentMethod, String status, Instant createdAt,
                        Instant paidAt, long version, List<Item> items) { }
}
