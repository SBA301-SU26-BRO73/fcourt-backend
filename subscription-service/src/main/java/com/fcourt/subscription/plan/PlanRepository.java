package com.fcourt.subscription.plan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code subscription_plans} and {@code plan_features}; soft-deleted plans are invisible. */
@Repository
class PlanRepository {
    private static final String COLUMNS = """
            SELECT id, name, tagline, monthly_price, yearly_price, max_courts, max_branches, is_active,
                   color, popular, created_at, updated_at
            FROM subscription_plans WHERE deleted_at IS NULL""";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** A plan row without its feature bullets; see {@link #featuresOf}. */
    record PlanRecord(long id, String name, String tagline, Long monthlyPrice, Long yearlyPrice, int maxCourts,
                      int maxBranches, boolean active, String color, boolean popular, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt) {
    }

    List<PlanRecord> findPage(int limit, long offset) {
        return jdbc.query(COLUMNS + " ORDER BY id LIMIT ? OFFSET ?", this::plan, limit, offset);
    }

    long count() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM subscription_plans WHERE deleted_at IS NULL",
                Long.class);
        return total == null ? 0 : total;
    }

    /** Plans on sale, cheapest monthly price first; plans with no monthly price come last. */
    List<PlanRecord> findActive() {
        return jdbc.query(COLUMNS + " AND is_active ORDER BY monthly_price ASC NULLS LAST, id", this::plan);
    }

    Optional<PlanRecord> find(long id) {
        return jdbc.query(COLUMNS + " AND id = ?", this::plan, id).stream().findFirst();
    }

    /** Deleted plans too: a subscription keeps pointing at the plan it bought. */
    Optional<PlanRecord> findIncludingDeleted(long id) {
        return jdbc.query("""
                SELECT id, name, tagline, monthly_price, yearly_price, max_courts, max_branches, is_active,
                       color, popular, created_at, updated_at
                FROM subscription_plans WHERE id = ?""", this::plan, id).stream().findFirst();
    }

    /**
     * Case-insensitive, like the {@code lower(name)} unique index. {@code exceptId} lets an update keep its
     * own name (or change only its case); pass 0 when creating.
     */
    boolean nameInUse(String name, long exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM subscription_plans WHERE lower(name) = lower(?) "
                        + "AND deleted_at IS NULL AND id <> ?)",
                Boolean.class, name, exceptId));
    }

    Map<Long, List<String>> featuresOf(Collection<Long> planIds) {
        Map<Long, List<String>> features = new LinkedHashMap<>();
        if (planIds.isEmpty()) {
            return features;
        }
        named.query("SELECT plan_id, feature FROM plan_features WHERE plan_id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", planIds), (ResultSet row) -> {
                    features.computeIfAbsent(row.getLong("plan_id"), key -> new ArrayList<>())
                            .add(row.getString("feature"));
                });
        return features;
    }

    long insert(PlanValues values, Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO subscription_plans (name, tagline, monthly_price, yearly_price, max_courts,
                    max_branches, is_active, color, popular, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                values.name(), values.tagline(), values.monthlyPrice(), values.yearlyPrice(), values.maxCourts(),
                values.maxBranches(), values.active(), values.color(), values.popular(), Timestamp.from(at),
                Timestamp.from(at));
        return id == null ? 0 : id;
    }

    void update(long id, PlanValues values, Instant at) {
        jdbc.update("""
                UPDATE subscription_plans SET name = ?, tagline = ?, monthly_price = ?, yearly_price = ?,
                    max_courts = ?, max_branches = ?, is_active = ?, color = ?, popular = ?, updated_at = ?
                WHERE id = ? AND deleted_at IS NULL""",
                values.name(), values.tagline(), values.monthlyPrice(), values.yearlyPrice(), values.maxCourts(),
                values.maxBranches(), values.active(), values.color(), values.popular(), Timestamp.from(at), id);
    }

    void replaceFeatures(long planId, List<String> features) {
        jdbc.update("DELETE FROM plan_features WHERE plan_id = ?", planId);
        jdbc.batchUpdate("INSERT INTO plan_features (plan_id, feature) VALUES (?, ?)",
                features, features.size(), (statement, feature) -> {
                    statement.setLong(1, planId);
                    statement.setString(2, feature);
                });
    }

    void softDelete(long id, Instant at) {
        jdbc.update("UPDATE subscription_plans SET deleted_at = ?, updated_at = ? WHERE id = ? "
                + "AND deleted_at IS NULL", Timestamp.from(at), Timestamp.from(at), id);
    }

    /** Writable columns, already normalised by the service. */
    record PlanValues(String name, String tagline, Long monthlyPrice, Long yearlyPrice, int maxCourts,
                      int maxBranches, boolean active, String color, boolean popular) {
    }

    private PlanRecord plan(ResultSet row, int index) throws SQLException {
        return new PlanRecord(row.getLong("id"), row.getString("name"), row.getString("tagline"),
                row.getObject("monthly_price", Long.class), row.getObject("yearly_price", Long.class),
                row.getInt("max_courts"), row.getInt("max_branches"), row.getBoolean("is_active"),
                row.getString("color"), row.getBoolean("popular"),
                row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class));
    }
}
