package com.fcourt.discovery.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.discovery.dto.request.CreateReviewRequest;
import com.fcourt.discovery.dto.response.ReviewPage;
import com.fcourt.discovery.model.Review;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.json = json;
    }

    public Review insert(long branchId, long courtId, long userId, CreateReviewRequest request) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO reviews(branch_id,court_id,booking_id,user_id,rating,comment,image_urls)
                    VALUES (?,?,?,?,?,?,?::jsonb) RETURNING *
                    """, this::row, branchId, courtId, request.bookingId(), userId, request.rating(), request.comment(),
                    json.writeValueAsString(request.imageUrls()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    public void updateRating(long branchId, int rating) {
        long[] counts = new long[5]; counts[rating - 1] = 1;
        jdbc.update("""
                INSERT INTO branch_review_stats(branch_id,review_count,rating_sum,star_1,star_2,star_3,star_4,star_5)
                VALUES (?,1,?,?,?,?,?,?) ON CONFLICT(branch_id) DO UPDATE SET
                    review_count=branch_review_stats.review_count+1,rating_sum=branch_review_stats.rating_sum+EXCLUDED.rating_sum,
                    star_1=branch_review_stats.star_1+EXCLUDED.star_1,star_2=branch_review_stats.star_2+EXCLUDED.star_2,
                    star_3=branch_review_stats.star_3+EXCLUDED.star_3,star_4=branch_review_stats.star_4+EXCLUDED.star_4,
                    star_5=branch_review_stats.star_5+EXCLUDED.star_5
                """, branchId, rating, counts[0], counts[1], counts[2], counts[3], counts[4]);
        jdbc.update("UPDATE review_cache_version SET version=version+1 WHERE id=1");
    }

    public boolean publicBranch(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM branch_read_model WHERE id=? AND active)", Boolean.class, id));
    }

    public ReviewPage page(long branchId, int page, int size) {
        var stats = jdbc.queryForList("SELECT * FROM branch_review_stats WHERE branch_id=?", branchId);
        long count = stats.isEmpty() ? 0 : ((Number) stats.getFirst().get("review_count")).longValue();
        long sum = stats.isEmpty() ? 0 : ((Number) stats.getFirst().get("rating_sum")).longValue();
        var distribution = new ArrayList<ReviewPage.StarCount>();
        for (int star = 1; star <= 5; star++) {
            long votes = stats.isEmpty() ? 0 : ((Number) stats.getFirst().get("star_" + star)).longValue();
            BigDecimal percent = count == 0 ? new BigDecimal("0.00")
                    : BigDecimal.valueOf(votes).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            distribution.add(new ReviewPage.StarCount(star, votes, percent));
        }
        var reviews = jdbc.query("SELECT * FROM reviews WHERE branch_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::row, branchId, size, (long) page * size);
        BigDecimal average = count == 0 ? new BigDecimal("0.00") : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new ReviewPage(reviews, page, size, count, (count + size - 1) / size, average, distribution);
    }

    public Optional<Review> reply(long id, List<Long> branches, long actor, String text) {
        if (branches.isEmpty()) return Optional.empty();
        return named.query("UPDATE reviews SET admin_reply=:reply,reply_author_id=:actor,replied_at=now() WHERE id=:id AND branch_id IN (:branches) RETURNING *",
                new MapSqlParameterSource("reply", text).addValue("actor", actor).addValue("id", id).addValue("branches", branches), this::row)
                .stream().findFirst();
    }

    private Review row(ResultSet row, int index) throws SQLException {
        try {
            var replied = row.getTimestamp("replied_at");
            return new Review(row.getLong("id"), row.getLong("branch_id"), row.getLong("court_id"), row.getLong("booking_id"),
                    row.getLong("user_id"), row.getInt("rating"), row.getString("comment"),
                    json.readValue(row.getString("image_urls"), new TypeReference<List<String>>() {}), row.getString("admin_reply"),
                    row.getTimestamp("created_at").toInstant(), replied == null ? null : replied.toInstant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Invalid stored review images", error); }
    }
}
