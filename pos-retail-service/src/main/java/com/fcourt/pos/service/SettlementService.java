package com.fcourt.pos.service;

import com.fcourt.pos.api.PosRequests;
import com.fcourt.pos.persistence.PosStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SettlementService {
    private final JdbcTemplate jdbc;
    private final PosStore store;
    private final PosCommands commands;
    private final Clock clock;
    public SettlementService(JdbcTemplate jdbc, PosStore store, PosCommands commands, Clock clock) {
        this.jdbc = jdbc; this.store = store; this.commands = commands; this.clock = clock;
    }
    @Transactional(timeout = 15)
    public void settle(PosRequests.Settlement request) {
        if (request.paidAt().isAfter(clock.instant().plusSeconds(60))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Settlement time is in the future");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "settlement:" + request.eventId());
        String hash = commands.fingerprint(request);
        var existing = jdbc.queryForList("SELECT fingerprint FROM pos_settlement_events WHERE event_id = ?", request.eventId());
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get("fingerprint"))) throw commands.conflict();
            return;
        }
        var codes = new HashSet<String>();
        for (var charge : request.orders().stream().sorted(Comparator.comparing(PosRequests.SettledCharge::orderCode)).toList()) {
            if (!codes.add(charge.orderCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate settlement order");
            var ids = jdbc.queryForList("SELECT id FROM orders WHERE order_code = ?", Long.class, charge.orderCode());
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement order is not available yet");
            var order = store.order(ids.getFirst(), true);
            if (order.bookingId() == null || order.bookingId() != request.bookingId() || order.branchId() != request.branchId()
                    || !"BOOKING".equals(order.paymentMethod()) || order.totalAmount().compareTo(charge.amount()) != 0
                    || !("ON_BOOKING".equals(order.status()) || "PAID".equals(order.status()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement does not match attached charge");
            }
            if (!"PAID".equals(order.status())) {
                jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ?, version = version + 1 WHERE id = ?", Timestamp.from(request.paidAt()), order.id());
                commands.event("PosOrderPaidEvent", store.order(order.id(), false));
            }
        }
        jdbc.update("INSERT INTO pos_settlement_events(event_id, fingerprint) VALUES (?, ?)", request.eventId(), hash);
    }
}
