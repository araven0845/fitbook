package com.fitbook.repository;

import com.fitbook.dto.ServiceDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcServiceRepository implements ServiceRepository {

    private static final RowMapper<ServiceDTO> ROW_MAPPER = (rs, rowNum) -> new ServiceDTO(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getInt("duration_minutes"),
            rs.getDouble("price"),
            rs.getString("description")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcServiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ServiceDTO> findAll() {
        String sql = """
                SELECT id, name, duration_minutes, price, description
                FROM services
                ORDER BY name
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }
}
