package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.exception.BadRequestException;
import com.fitbook.exception.ConflictException;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.model.AppointmentRecord;
import com.fitbook.model.AppointmentStatus;
import com.fitbook.model.SlotRecord;
import com.fitbook.model.SlotStatus;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.repository.SlotRepository;
import com.fitbook.util.SlotTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * The transactional core of booking and cancelling. Each public method is ONE database
 * transaction: every read, check and write below commits together or rolls back together
 * (atomicity), and the checks plus the version-guarded UPDATE keep the slot/appointment pair
 * consistent (consistency). It is a separate bean from BookingServiceImpl so the retry loop
 * there wraps the @Transactional proxy - each retry gets a fresh transaction.
 *
 * Isolation is SERIALIZABLE, which is what SQLite provides natively (a single writer, and with
 * BEGIN IMMEDIATE the write lock is taken at transaction start). Concurrency control is
 * optimistic: the booking UPDATE only succeeds if the slot still has the version we read.
 */
@Component
public class BookingTransactions {

    private final SlotRepository slotRepository;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;

    public BookingTransactions(SlotRepository slotRepository, AppointmentRepository appointmentRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AppointmentDTO book(long customerId, BookingRequest request) {
        String now = SlotTime.now(clock);

        SlotRecord slot = slotRepository.findById(request.slotId())
                .orElseThrow(() -> new NotFoundException("Slot not found"));
        if (slot.serviceId() != request.serviceId()) {
            throw new BadRequestException("The chosen service does not match this slot");
        }
        if (slot.status() != SlotStatus.OPEN) {
            throw new ConflictException("This slot is no longer available");
        }
        if (slot.startTime().compareTo(now) <= 0) {
            throw new ConflictException("This slot has already started");
        }

        // Optimistic version check: zero rows updated means another transaction changed the slot
        // after we read it. Retryable - the retry re-reads and reports whatever state it is in now.
        if (slotRepository.markBooked(slot.id(), slot.version()) == 0) {
            throw new OptimisticLockingFailureException("Slot " + slot.id() + " changed concurrently");
        }

        long appointmentId;
        try {
            appointmentId = appointmentRepository.insert(slot.id(), customerId, slot.serviceId());
        } catch (DuplicateKeyException e) {
            // Database-level backstop (partial unique index on appointments.slot_id) caught a double booking.
            throw new ConflictException("This slot has just been booked by someone else");
        }
        return appointmentRepository.findDetailById(appointmentId, now)
                .orElseThrow(() -> new IllegalStateException("Appointment " + appointmentId + " vanished after insert"));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void cancel(long customerId, long appointmentId) {
        String now = SlotTime.now(clock);

        AppointmentRecord appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (appointment.customerId() != customerId) {
            throw new ForbiddenException("You can only cancel your own appointments");
        }
        if (appointment.status() != AppointmentStatus.BOOKED) {
            throw new ConflictException("Only booked appointments can be cancelled");
        }
        SlotRecord slot = slotRepository.findById(appointment.slotId())
                .orElseThrow(() -> new IllegalStateException("Appointment " + appointmentId + " has no slot"));
        if (slot.startTime().compareTo(now) <= 0) {
            throw new ConflictException("This appointment has already started and can no longer be cancelled");
        }

        if (appointmentRepository.markCancelled(appointmentId) == 0) {
            throw new ConflictException("This appointment was already cancelled");
        }
        if (slotRepository.reopen(slot.id()) == 0) {
            throw new IllegalStateException("Slot " + slot.id() + " was not BOOKED while its appointment was active");
        }
    }
}
