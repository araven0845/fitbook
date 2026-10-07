package com.fitbook.dto;

/**
 * Strict DTO for an appointment. `status` is BOOKED, CANCELLED, or COMPLETED (a BOOKED
 * appointment whose slot has already ended reads back as COMPLETED).
 */
public record AppointmentDTO(
        long id,
        long slotId,
        String providerName,
        String customerName,
        String serviceName,
        String startTime,
        String endTime,
        String status,
        String bookedAt
) {}
