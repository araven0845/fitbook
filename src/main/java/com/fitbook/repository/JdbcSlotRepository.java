package com.fitbook.repository;

import com.fitbook.dto.AvailabilitySlotDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written SQL over JDBC (JdbcTemplate is a thin helper around
 * java.sql.Connection/PreparedStatement - it performs no entity mapping,
 * lazy loading, or query generation, so it is not an ORM). Filtering and
 * pagination are both expressed directly in SQL, per the assignment spec.
 */
@Repository
public class JdbcSlotRepository implements SlotRepository {

    private static final RowMapper<AvailabilitySlotDTO> ROW_MAPPER = (rs, rowNum) -> new AvailabilitySlotDTO(
            rs.getLong("id"),
            rs.getLong("provider_id"),
            rs.getString("provider_name"),
            rs.getLong("service_id"),
            rs.getString("service_name"),
            rs.getString("start_time"),
            rs.getString("end_time"),
            rs.getString("status")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcSlotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AvailabilitySlotDTO> findAvailable(Long providerId, Long serviceId, String date, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS id,
                       s.provider_id AS provider_id, u.full_name AS provider_name,
                       s.service_id AS service_id, sv.name AS service_name,
                       s.start_time AS start_time, s.end_time AS end_time, s.status AS status
                FROM availability_slots s
                JOIN providers p ON p.id = s.provider_id
                JOIN users u ON u.id = p.user_id
                JOIN services sv ON sv.id = s.service_id
                WHERE s.status = 'OPEN'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, providerId, serviceId, date);
        sql.append(" ORDER BY s.start_time LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    @Override
    public long countAvailable(Long providerId, Long serviceId, String date) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM availability_slots s
                WHERE s.status = 'OPEN'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, providerId, serviceId, date);

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    private void appendFilters(StringBuilder sql, List<Object> params, Long providerId, Long serviceId, String date) {
        if (providerId != null) {
            sql.append(" AND s.provider_id = ?");
            params.add(providerId);
        }
        if (serviceId != null) {
            sql.append(" AND s.service_id = ?");
            params.add(serviceId);
        }
        if (date != null && !date.isBlank()) {
            sql.append(" AND date(s.start_time) = date(?)");
            params.add(date);
        }
    }
}
