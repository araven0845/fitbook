package com.fitbook.repository;

import com.fitbook.dto.ProviderSummaryDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcProviderRepository implements ProviderRepository {

    private static final RowMapper<ProviderSummaryDTO> ROW_MAPPER = (rs, rowNum) -> new ProviderSummaryDTO(
            rs.getLong("id"),
            rs.getString("full_name"),
            rs.getString("specialty")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ProviderSummaryDTO> findAll() {
        // Join providers -> users to surface the trainer's display name,
        // never exposing the users row (password_hash etc.) itself.
        String sql = """
                SELECT p.id AS id, u.full_name AS full_name, p.specialty AS specialty
                FROM providers p
                JOIN users u ON u.id = p.user_id
                ORDER BY u.full_name
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }
}
