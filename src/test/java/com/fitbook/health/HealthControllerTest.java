package com.fitbook.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void health_whenDatabaseAnswers_is200AndUp() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        ResponseEntity<HealthResponse> response = new HealthController(jdbcTemplate, Clock.systemUTC()).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().status());
        assertEquals("UP", response.getBody().checks().get("database").status());
    }

    @Test
    void health_whenDatabaseIsUnreachable_is503AndDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(new DataAccessResourceFailureException("db gone"));

        ResponseEntity<HealthResponse> response = new HealthController(jdbcTemplate, Clock.systemUTC()).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().status());
        assertEquals("DOWN", response.getBody().checks().get("database").status());
    }
}
