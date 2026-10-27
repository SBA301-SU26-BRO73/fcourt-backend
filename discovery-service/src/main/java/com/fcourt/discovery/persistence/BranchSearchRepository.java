package com.fcourt.discovery.persistence;

import com.fcourt.discovery.api.BranchSearchRequest;
import com.fcourt.discovery.api.BranchSearchResponse;
import com.fcourt.discovery.api.BranchSearchResponse.BranchSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BranchSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public BranchSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchSearchResponse search(BranchSearchRequest request) {
        var params = new MapSqlParameterSource();
        var where = new StringBuilder(" WHERE b.active");
        if (hasText(request.query())) {
            params.addValue("query", request.query().trim());
            params.addValue("pattern", "%" + request.query().trim()
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where.append("""
                     AND (b.search_text LIKE lower(unaccent(:pattern)) ESCAPE '!'
                          OR b.search_text %> lower(unaccent(:query)))
                    """);
        }
        if (hasText(request.city())) {
            where.append(" AND b.city_key = lower(unaccent(:city))");
            params.addValue("city", request.city().trim());
        }
        if (hasText(request.district())) {
            where.append(" AND b.district_key = lower(unaccent(:district))");
            params.addValue("district", request.district().trim());
        }
        if (request.courtTypeId() != null || request.minPrice() != null || request.maxPrice() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM court_read_model c WHERE c.branch_id = b.id");
            if (request.courtTypeId() != null) {
                where.append(" AND c.court_type_id = :courtTypeId");
                params.addValue("courtTypeId", request.courtTypeId());
            }
            if (request.minPrice() != null) {
                where.append(" AND c.max_price >= :minPrice");
                params.addValue("minPrice", request.minPrice());
            }
            if (request.maxPrice() != null) {
                where.append(" AND c.min_price <= :maxPrice");
                params.addValue("maxPrice", request.maxPrice());
            }
            where.append(")");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM branch_read_model b" + where, params, Long.class);
        String distance = "NULL::double precision";
        String order = "b.id";
        if (request.latitude() != null) {
            params.addValue("lat", request.latitude()).addValue("lon", request.longitude());
            distance = """
                    CASE WHEN b.latitude IS NULL THEN NULL ELSE
                    6371.0088 * 2 * asin(sqrt(least(1.0, greatest(0.0,
                        power(sin(radians(b.latitude - :lat) / 2), 2)
                        + cos(radians(:lat)) * cos(radians(b.latitude))
                        * power(sin(radians(b.longitude - :lon) / 2), 2))))) END
                    """;
            order = "distance_km ASC NULLS LAST, b.id";
        } else if (hasText(request.query())) {
            order = "word_similarity(lower(unaccent(:query)), b.search_text) DESC, b.id";
        }
        params.addValue("limit", request.pageSize())
                .addValue("offset", (long) request.pageNumber() * request.pageSize());
        // Aggregate only the selected page, keeping counts/prices over all active courts.
        String sql = "SELECT p.*, a.min_price, a.max_price, a.court_count FROM (SELECT b.*, "
                + distance + " AS distance_km FROM branch_read_model b" + where
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset) p "
                + "LEFT JOIN LATERAL (SELECT min(c.min_price) AS min_price, max(c.max_price) AS max_price, "
                + "count(*) AS court_count FROM court_read_model c WHERE c.branch_id = p.id) a ON true "
                + (request.latitude() != null ? "ORDER BY p.distance_km ASC NULLS LAST, p.id"
                : hasText(request.query()) ? "ORDER BY word_similarity(lower(unaccent(:query)), p.search_text) DESC, p.id"
                : "ORDER BY p.id");
        var content = jdbc.query(sql, params, (rs, row) -> new BranchSummary(
                rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getString("city"), rs.getString("district"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
                rs.getBigDecimal("min_price"), rs.getBigDecimal("max_price"),
                rs.getLong("court_count"), rs.getObject("distance_km", Double.class)));
        return new BranchSearchResponse(content, request.pageNumber(), request.pageSize(), total,
                (total + request.pageSize() - 1) / request.pageSize());
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
