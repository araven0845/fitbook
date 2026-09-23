package com.fitbook.dto;

/**
 * Strict DTO returned by controllers - never the raw `availability_slots` row.
 */
public record AvailabilitySlotDTO(
        long id,
        long providerId,
        String providerName,
        long serviceId,
        String serviceName,
        String startTime,
        String endTime,
        String status
) {}
