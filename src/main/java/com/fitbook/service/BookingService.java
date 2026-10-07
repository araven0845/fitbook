package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.MyAppointmentsDTO;

public interface BookingService {

    /** Books the slot for the customer. Exactly one of several concurrent callers wins; the rest get a 409. */
    AppointmentDTO book(long customerId, BookingRequest request);

    /** Cancels one of the customer's own appointments and re-opens the slot. */
    void cancel(long customerId, long appointmentId);

    /** Owner-only read of a single appointment (the confirmation page). */
    AppointmentDTO getAppointment(long customerId, long appointmentId);

    MyAppointmentsDTO listAppointments(long customerId);
}
