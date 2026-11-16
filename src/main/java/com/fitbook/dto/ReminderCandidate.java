package com.fitbook.dto;

/** One upcoming appointment the reminder agent still has to remind the customer about. */
public record ReminderCandidate(
        long appointmentId,
        long customerId,
        String customerName,
        String serviceName,
        String providerName,
        String startTime,
        String endTime
) {}
