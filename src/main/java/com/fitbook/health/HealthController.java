package com.fitbook.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * GET /health: 200 and status UP when the app can reach its database, 503 and status DOWN when it
 * cannot. The database check runs a real query (SELECT 1), so it proves connectivity rather than
 * just that the process is alive. No login is required, so load balancers and Docker can poll it.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HealthController(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        long started = System.nanoTime();
        boolean databaseUp;
        try {
            databaseUp = Integer.valueOf(1).equals(jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            log.error("Health check failed: database unreachable: {}", e.getMessage());
            databaseUp = false;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        HealthResponse body = new HealthResponse(
                databaseUp ? "UP" : "DOWN",
                Map.of("database", new HealthResponse.Check(databaseUp ? "UP" : "DOWN", elapsedMs)),
                Instant.now(clock).toString());
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
