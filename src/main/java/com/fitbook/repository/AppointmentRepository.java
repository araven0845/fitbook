package com.fitbook.repository;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.model.AppointmentRecord;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository {

    /** Inserts a BOOKED appointment. The partial unique index rejects a second active booking of the slot. */
    long insert(long slotId, long customerId, long serviceId);

    Optional<AppointmentRecord> findById(long id);

    /** @param now current local time, used to derive COMPLETED for BOOKED appointments whose slot has ended */
    Optional<AppointmentDTO> findDetailById(long id, String now);

    List<AppointmentDTO> findByCustomer(long customerId, String now);

    List<AppointmentDTO> findByProvider(long providerId, String now);

    /** BOOKED -> CANCELLED. Returns 0 rows if the appointment was not BOOKED. */
    int markCancelled(long id);
}
