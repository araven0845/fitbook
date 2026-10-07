package com.fitbook.model;

/** Internal row representation of `appointments` (stored status, not the derived COMPLETED view). */
public record AppointmentRecord(long id, long slotId, long customerId, long serviceId, AppointmentStatus status) {}
