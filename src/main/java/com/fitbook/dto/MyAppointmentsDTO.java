package com.fitbook.dto;

import java.util.List;

/** A customer's appointments split into upcoming (still BOOKED) and history (CANCELLED/COMPLETED). */
public record MyAppointmentsDTO(List<AppointmentDTO> upcoming, List<AppointmentDTO> history) {}
