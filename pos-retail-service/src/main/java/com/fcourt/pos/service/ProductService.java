package com.fcourt.pos.service;

import com.fcourt.pos.api.PosRequests;
import com.fcourt.pos.api.PosViews;
import com.fcourt.pos.dto.response.ProductPageResponse;
import com.fcourt.pos.integration.PosUpstreams;
import com.fcourt.pos.persistence.PosStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
    private final JdbcTemplate jdbc;
    private final PosStore store;
    private final PosUpstreams access;
    private final PosCommands commands;
    private final TransactionTemplate transaction;
    public ProductService(JdbcTemplate jdbc, PosStore store, PosUpstreams access, PosCommands commands,
                          PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.store = store; this.access = access; this.commands = commands;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(15);
    }
    public List<PosViews.Product> catalog(Jwt jwt, String query, String category, int limit, int offset) {
        return store.catalog(access.staffBranch(jwt), query, category, limit, offset);
    }
    public ProductPageResponse adminList(Jwt jwt, long branch, String query, Boolean active, int page, int size) {
        access.adminBranch(jwt, branch);
        String q = query == null ? "" : query.strip();
        return transaction.execute(status -> new ProductPageResponse(store.adminProducts(branch, q, active, size, page * size),
                page, size, store.adminProductCount(branch, q, active)));
    }
    /** Read-only list across every branch (optionally one branch) for SUPER_ADMIN. */
    public ProductPageResponse superAdminList(Jwt jwt, Long branch, String query, Boolean active, int page, int size) {
        access.superAdmin(jwt);
        String q = query == null ? "" : query.strip();
        return transaction.execute(status -> new ProductPageResponse(store.allProducts(branch, q, active, size, page * size),
                page, size, store.allProductCount(branch, q, active)));
    }
    public PosViews.Product create(Jwt jwt, UUID key, PosRequests.Product request) {
        long actor = Long.parseLong(jwt.getSubject());
        access.adminBranch(jwt, request.branchId());
        return transaction.execute(status -> insert(actor, key, request));
    }
    private PosViews.Product insert(long actor, UUID key, PosRequests.Product request) {
        commands.lock(actor, key);
        String hash = commands.fingerprint(request);
        Long replay = commands.productReplay(actor, key, "CREATE", hash);
        if (replay != null) return store.product(replay, false);
        long id = jdbc.queryForObject("INSERT INTO products(branch_id, name, unit, category, price, stock_quantity, image_url) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, request.branchId(), request.name().strip(), request.unit(), request.category().strip(), request.price(), request.stockQuantity(), request.imageUrl());
        commands.stockMovement(id, actor, key, "INITIAL", request.stockQuantity(), "initial stock", null);
        commands.recordProduct(actor, key, "CREATE", hash, id);
        return store.product(id, false);
    }
    public PosViews.Product update(Jwt jwt, long id, UUID key, PosRequests.ProductUpdate request) {
        long actor = Long.parseLong(jwt.getSubject());
        long authorizedBranch = store.product(id, false).branchId();
        access.adminBranch(jwt, authorizedBranch);
        return transaction.execute(status -> change(actor, id, key, request, authorizedBranch));
    }
    private PosViews.Product change(long actor, long id, UUID key, PosRequests.ProductUpdate request, long authorizedBranch) {
        commands.lock(actor, key);
        if (store.product(id, true).branchId() != authorizedBranch) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product ownership changed");
        String hash = commands.fingerprint(List.of(id, request));
        Long replay = commands.productReplay(actor, key, "UPDATE", hash);
        if (replay != null) return store.product(replay, false);
        jdbc.update("UPDATE products SET name = ?, unit = ?, category = ?, price = ?, active = ?, image_url = ?, updated_at = now() WHERE id = ?",
                request.name().strip(), request.unit(), request.category().strip(), request.price(), request.active(), request.imageUrl(), id);
        commands.recordProduct(actor, key, "UPDATE", hash, id);
        return store.product(id, false);
    }
    public PosViews.Product adjust(Jwt jwt, long id, UUID key, PosRequests.StockAdjustment request) {
        if (request.delta() == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock delta must not be zero");
        long actor = Long.parseLong(jwt.getSubject());
        long authorizedBranch = store.product(id, false).branchId();
        access.adminBranch(jwt, authorizedBranch);
        return transaction.execute(status -> move(actor, id, key, request, authorizedBranch));
    }
    private PosViews.Product move(long actor, long id, UUID key, PosRequests.StockAdjustment request, long authorizedBranch) {
        commands.lock(actor, key);
        var product = store.product(id, true);
        if (product.branchId() != authorizedBranch) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product ownership changed");
        String hash = commands.fingerprint(List.of(id, request));
        Long replay = commands.productReplay(actor, key, "ADJUST", hash);
        if (replay != null) return store.product(replay, false);
        long total = product.stockQuantity() + (long) request.delta();
        if (total < 0 || total > 1000000000) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock adjustment exceeds bounds");
        jdbc.update("UPDATE products SET stock_quantity = ?, updated_at = now() WHERE id = ?", total, id);
        commands.stockMovement(id, actor, key, "ADJUST", request.delta(), request.reason().strip(), null);
        commands.recordProduct(actor, key, "ADJUST", hash, id);
        return store.product(id, false);
    }
}
