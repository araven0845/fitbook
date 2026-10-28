package com.fitbook.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationLogRepository implements NotificationLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIfAbsent(NotificationRequest request) {
        String sql = "INSERT INTO notifications (idempotency_key, recipient, type, message) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(idempotency_key) DO NOTHING";
        return jdbcTemplate.update(sql, request.idempotencyKey(), request.recipient(), request.type().name(), request.message()) == 1;
    }
}
