package com.fcourt.facility.persistence;

import com.fcourt.facility.api.BranchImageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BranchImageStore {
    /** An image row together with the storage handle needed to delete the remote file. */
    public record Stored(BranchImageResponse image, String publicId) { }

    private final JdbcTemplate jdbc;

    public BranchImageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BranchImageResponse> list(long branchId) {
        return jdbc.query("SELECT * FROM branch_images WHERE branch_id = ? ORDER BY sort_order, id", this::row, branchId);
    }

    public List<BranchImageResponse> listFor(Collection<Long> branchIds) {
        if (branchIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM branch_images WHERE branch_id = ANY (?) ORDER BY branch_id, sort_order, id",
                this::row, (Object) branchIds.toArray(Long[]::new));
    }

    public int count(long branchId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM branch_images WHERE branch_id = ?", Integer.class, branchId);
        return count == null ? 0 : count;
    }

    public BranchImageResponse insert(long branchId, String url, String publicId) {
        return jdbc.queryForObject("""
                INSERT INTO branch_images (branch_id, url, public_id, sort_order)
                VALUES (?, ?, ?, (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM branch_images WHERE branch_id = ?))
                RETURNING *
                """, this::row, branchId, url, publicId, branchId);
    }

    public Optional<Stored> find(long imageId) {
        return jdbc.query("SELECT * FROM branch_images WHERE id = ?",
                (rs, n) -> new Stored(row(rs, n), rs.getString("public_id")), imageId).stream().findFirst();
    }

    public void delete(long imageId) {
        jdbc.update("DELETE FROM branch_images WHERE id = ?", imageId);
    }

    private BranchImageResponse row(ResultSet rs, int n) throws SQLException {
        return new BranchImageResponse(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("url"),
                rs.getInt("sort_order"), rs.getTimestamp("created_at").toInstant());
    }
}
