package com.fitbook.repository;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.model.AppointmentRecord;
import com.fitbook.model.AppointmentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {

    // The first '?' is `now`: a BOOKED appointment whose slot has ended reads back as COMPLETED.
    private static final String DETAIL_SELECT = """
            SELECT a.id AS id, a.slot_id AS slot_id,
                   pu.full_name AS provider_name, cu.full_name AS customer_name,
                   sv.name AS service_name,
                   s.start_time AS start_time, s.end_time AS end_time,
                   CASE WHEN a.status = 'BOOKED' AND s.end_time <= ? THEN 'COMPLETED' ELSE a.status END AS status,
                   a.created_at AS created_at
            FROM appointments a
            JOIN availability_slots s ON s.id = a.slot_id
            JOIN providers p ON p.id = s.provider_id
            JOIN users pu ON pu.id = p.user_id
            JOIN users cu ON cu.id = a.customer_id
            JOIN services sv ON sv.id = a.service_id
            """;

    private static final RowMapper<AppointmentDTO> DTO_MAPPER = (rs, rowNum) -> new AppointmentDTO(
            rs.getLong("id"),
            rs.getLong("slot_id"),
            rs.getString("provider_name"),
            rs.getString("customer_name"),
            rs.getString("service_name"),
            rs.getString("start_time"),
            rs.getString("end_time"),
            rs.getString("status"),
            rs.getString("created_at")
    );

    private static final RowMapper<AppointmentRecord> RECORD_MAPPER = (rs, rowNum) -> new AppointmentRecord(
            rs.getLong("id"),
            rs.getLong("slot_id"),
            rs.getLong("customer_id"),
            rs.getLong("service_id"),
            AppointmentStatus.valueOf(rs.getString("status"))
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppointmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(long slotId, long customerId, long serviceId) {
        String sql = "INSERT INTO appointments (slot_id, customer_id, service_id, status) VALUES (?, ?, ?, 'BOOKED')";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, slotId);
            ps.setLong(2, customerId);
            ps.setLong(3, serviceId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    @Override
    public Optional<AppointmentRecord> findById(long id) {
        String sql = "SELECT id, slot_id, customer_id, service_id, status FROM appointments WHERE id = ?";
        return jdbcTemplate.query(sql, RECORD_MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<AppointmentDTO> findDetailById(long id, String now) {
        return jdbcTemplate.query(DETAIL_SELECT + " WHERE a.id = ?", DTO_MAPPER, now, id).stream().findFirst();
    }

    @Override
    public List<AppointmentDTO> findByCustomer(long customerId, String now) {
        return jdbcTemplate.query(DETAIL_SELECT + " WHERE a.customer_id = ? ORDER BY s.start_time", DTO_MAPPER, now, customerId);
    }

    @Override
    public List<AppointmentDTO> findByProvider(long providerId, String now) {
        return jdbcTemplate.query(DETAIL_SELECT + " WHERE s.provider_id = ? ORDER BY s.start_time", DTO_MAPPER, now, providerId);
    }

    @Override
    public int markCancelled(long id) {
        return jdbcTemplate.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = ? AND status = 'BOOKED'", id);
    }
}
