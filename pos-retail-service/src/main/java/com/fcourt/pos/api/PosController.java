package com.fcourt.pos.api;

import com.fcourt.pos.service.ProductService;
import com.fcourt.pos.service.SalesService;
import com.fcourt.pos.service.SettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
class PosController {
    private final ProductService products;
    private final SalesService sales;
    private final SettlementService settlements;
    PosController(ProductService products, SalesService sales, SettlementService settlements) {
        this.products = products; this.sales = sales; this.settlements = settlements;
    }
    @GetMapping("/api/staff/pos/products")
    ResponseEntity<List<PosViews.Product>> catalog(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = "") @Size(max = 60) String category,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(products.catalog(jwt, query, category, limit, offset));
    }
    @PostMapping("/api/admin/pos/products")
    ResponseEntity<PosViews.Product> createProduct(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PosRequests.Product request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(products.create(jwt, key, request));
    }
    @PutMapping("/api/admin/pos/products/{id}")
    ResponseEntity<PosViews.Product> updateProduct(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody PosRequests.ProductUpdate request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(products.update(jwt, id, key, request));
    }
    @PostMapping("/api/admin/pos/products/{id}/stock-adjustments")
    ResponseEntity<PosViews.Product> adjustStock(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody PosRequests.StockAdjustment request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(products.adjust(jwt, id, key, request));
    }
    @PostMapping("/api/staff/pos/orders")
    ResponseEntity<PosViews.Order> createOrder(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PosRequests.Order request) {
        var result = sales.create(jwt, key, request);
        return ResponseEntity.status("PENDING_CHARGE".equals(result.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/staff/pos/orders/{id}")
    ResponseEntity<PosViews.Order> order(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sales.get(jwt, id));
    }
    @GetMapping("/api/staff/pos/orders")
    ResponseEntity<List<PosViews.Order>> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sales.history(jwt, limit, offset));
    }
    @PostMapping("/api/internal/pos/settlements")
    ResponseEntity<Void> settle(@Valid @RequestBody PosRequests.Settlement request) {
        settlements.settle(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
