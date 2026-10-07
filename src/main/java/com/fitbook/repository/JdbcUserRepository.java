package com.fitbook.repository;

import com.fitbook.model.Role;
import com.fitbook.model.UserRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            rs.getString("full_name")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, role, full_name FROM users WHERE username = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, username).stream().findFirst();
    }
}
