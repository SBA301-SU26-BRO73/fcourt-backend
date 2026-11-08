package com.fcourt.facility.persistence;

import com.fcourt.facility.api.PricingDtos.RuleRequest;
import com.fcourt.facility.service.PricingEngine;
import com.fcourt.facility.service.PricingEngine.Rule;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PricingRuleStore {
    private static final String LIVE = "is_active AND deleted_at IS NULL";
    private final JdbcTemplate jdbc;

    public PricingRuleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Rule> listLive(long courtId) {
        return jdbc.query("SELECT * FROM court_pricing_rules WHERE court_id = ? AND " + LIVE
                + " ORDER BY start_time, id", this::row, courtId);
    }

    public Optional<Rule> findLive(long courtId, long ruleId) {
        return jdbc.query("SELECT * FROM court_pricing_rules WHERE id = ? AND court_id = ? AND " + LIVE,
                this::row, ruleId, courtId).stream().findFirst();
    }

    public Rule insert(long courtId, RuleRequest request, java.util.Set<Integer> days, boolean peak, BigDecimal price) {
        return jdbc.queryForObject("""
                INSERT INTO court_pricing_rules (court_id, rule_name, start_time, end_time, price_per_hour,
                                                 is_peak_hour, days_of_week)
                VALUES (?, ?, ?, ?, ?, ?, ?::smallint[]) RETURNING *
                """, this::row, courtId, request.ruleName().trim(), Time.valueOf(request.startTime()),
                Time.valueOf(request.endTime()), price, peak, literal(days));
    }

    public Rule insertCopy(long courtId, Rule source, java.util.Set<Integer> days, BigDecimal price) {
        return jdbc.queryForObject("""
                INSERT INTO court_pricing_rules (court_id, rule_name, start_time, end_time, price_per_hour,
                                                 is_peak_hour, days_of_week)
                VALUES (?, ?, ?, ?, ?, ?, ?::smallint[]) RETURNING *
                """, this::row, courtId, source.name(), Time.valueOf(source.start()), Time.valueOf(source.end()),
                price, source.peak(), literal(days));
    }

    public Rule update(long ruleId, RuleRequest request, java.util.Set<Integer> days, boolean peak, BigDecimal price) {
        return jdbc.queryForObject("""
                UPDATE court_pricing_rules SET rule_name = ?, start_time = ?, end_time = ?, price_per_hour = ?,
                       is_peak_hour = ?, days_of_week = ?::smallint[], updated_at = now()
                WHERE id = ? RETURNING *
                """, this::row, request.ruleName().trim(), Time.valueOf(request.startTime()),
                Time.valueOf(request.endTime()), price, peak, literal(days), ruleId);
    }

    public void retire(long ruleId) {
        jdbc.update("UPDATE court_pricing_rules SET is_active = false, deleted_at = now(), updated_at = now()"
                + " WHERE id = ?", ruleId);
    }

    private static String literal(java.util.Set<Integer> days) {
        return "{" + String.join(",", new TreeSet<>(days).stream().map(String::valueOf).toList()) + "}";
    }

    private Rule row(ResultSet rs, int n) throws SQLException {
        Array days = rs.getArray("days_of_week");
        Number[] values = (Number[]) days.getArray();
        return new Rule(rs.getLong("id"), rs.getString("rule_name"), rs.getTime("start_time").toLocalTime(),
                rs.getTime("end_time").toLocalTime(), rs.getBigDecimal("price_per_hour"),
                rs.getBoolean("is_peak_hour"),
                new TreeSet<>(Arrays.stream(values).map(Number::intValue).toList()));
    }
}
