package com.fitbook.health;

import java.util.Map;

/** Body of GET /health: overall status plus one entry per dependency check. */
public record HealthResponse(String status, Map<String, Check> checks, String timestamp) {

    public record Check(String status, long responseTimeMs) {}
}
