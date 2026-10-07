package com.fitbook.repository;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.model.SlotRecord;
import com.fitbook.model.SlotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private static final RowMapper<SlotRecord> RECORD_MAPPER = (rs, rowNum) -> new SlotRecord(
            rs.getLong("id"),
            rs.getLong("provider_id"),
            rs.getLong("service_id"),
            rs.getString("start_time"),
            rs.getString("end_time"),
            SlotStatus.valueOf(rs.getString("status")),
            rs.getLong("version")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcSlotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AvailabilitySlotDTO> findAvailable(Long providerId, Long serviceId, String date, String now, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS id,
                       s.provider_id AS provider_id, u.full_name AS provider_name,
                       s.service_id AS service_id, sv.name AS service_name,
                       s.start_time AS start_time, s.end_time AS end_time, s.status AS status
                FROM availability_slots s
                JOIN providers p ON p.id = s.provider_id
                JOIN users u ON u.id = p.user_id
                JOIN services sv ON sv.id = s.service_id
                WHERE s.status = 'OPEN' AND s.start_time > ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(now);
        appendFilters(sql, params, providerId, serviceId, date);
        sql.append(" ORDER BY s.start_time LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    @Override
    public long countAvailable(Long providerId, Long serviceId, String date, String now) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM availability_slots s
                WHERE s.status = 'OPEN' AND s.start_time > ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(now);
        appendFilters(sql, params, providerId, serviceId, date);

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public Optional<SlotRecord> findById(long id) {
        String sql = "SELECT id, provider_id, service_id, start_time, end_time, status, version FROM availability_slots WHERE id = ?";
        return jdbcTemplate.query(sql, RECORD_MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<AvailabilitySlotDTO> findDetailById(long id) {
        String sql = """
                SELECT s.id AS id,
                       s.provider_id AS provider_id, u.full_name AS provider_name,
                       s.service_id AS service_id, sv.name AS service_name,
                       s.start_time AS start_time, s.end_time AS end_time, s.status AS status
                FROM availability_slots s
                JOIN providers p ON p.id = s.provider_id
                JOIN users u ON u.id = p.user_id
                JOIN services sv ON sv.id = s.service_id
                WHERE s.id = ?
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public int markBooked(long slotId, long expectedVersion) {
        String sql = "UPDATE availability_slots SET status = 'BOOKED', version = version + 1 "
                + "WHERE id = ? AND version = ? AND status = 'OPEN'";
        return jdbcTemplate.update(sql, slotId, expectedVersion);
    }

    @Override
    public int reopen(long slotId) {
        String sql = "UPDATE availability_slots SET status = 'OPEN', version = version + 1 "
                + "WHERE id = ? AND status = 'BOOKED'";
        return jdbcTemplate.update(sql, slotId);
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
