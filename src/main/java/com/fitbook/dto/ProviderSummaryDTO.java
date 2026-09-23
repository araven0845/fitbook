package com.fitbook.dto;

/**
 * Strict DTO returned by controllers - never the raw `providers`/`users` rows.
 */
public record ProviderSummaryDTO(
        long id,
        String fullName,
        String specialty
) {}
