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

        String distance = "NULL::double precision";
        String order = "b.id";
        String score = "0::real";
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
            score = "word_similarity((SELECT lower(unaccent(:query))), b.search_text)";
            order = "relevance DESC, b.id";
        }
        params.addValue("limit", request.pageSize())
                .addValue("offset", (long) request.pageNumber() * request.pageSize());
        // Aggregate only the selected page, keeping counts/prices over all active courts.
        String sql = "SELECT p.*, a.min_price, a.max_price, a.court_count FROM (SELECT b.*, "
                + "count(*) OVER() AS total_elements, " + score + " AS relevance, "
                + distance + " AS distance_km FROM branch_read_model b" + where
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset) p "
                + "LEFT JOIN LATERAL (SELECT min(c.min_price) AS min_price, max(c.max_price) AS max_price, "
                + "count(*) AS court_count FROM court_read_model c WHERE c.branch_id = p.id) a ON true "
                + (request.latitude() != null ? "ORDER BY p.distance_km ASC NULLS LAST, p.id"
                : hasText(request.query()) ? "ORDER BY p.relevance DESC, p.id"
                : "ORDER BY p.id");
        // Unaccent is stable rather than immutable: scalar subqueries normalize once per statement.
        for (String key : new String[]{"query", "pattern", "city", "district"}) {
            sql = sql.replace("lower(unaccent(:" + key + "))", "(SELECT lower(unaccent(:" + key + ")))" );
        }
        var totalRows = new long[1];
        var content = jdbc.query(sql, params, (rs, row) -> {
            totalRows[0] = rs.getLong("total_elements");
            return new BranchSummary(
                rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getString("city"), rs.getString("district"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
                rs.getBigDecimal("min_price"), rs.getBigDecimal("max_price"),
                rs.getLong("court_count"), rs.getObject("distance_km", Double.class));
        });
        // An empty/out-of-range page still needs an accurate total.
        long total = content.isEmpty()
                ? jdbc.queryForObject("SELECT count(*) FROM branch_read_model b" + where, params, Long.class)
                : totalRows[0];
        return new BranchSearchResponse(content, request.pageNumber(), request.pageSize(), total,
                (total + request.pageSize() - 1) / request.pageSize());
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
