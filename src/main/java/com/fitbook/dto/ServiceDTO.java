package com.fitbook.dto;

/**
 * Strict DTO returned by controllers - never the raw `services` row.
 */
public record ServiceDTO(
        long id,
        String name,
        int durationMinutes,
        double price,
        String description
) {}
