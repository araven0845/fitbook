package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.MyAppointmentsDTO;
import com.fitbook.exception.ConflictException;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.model.AppointmentRecord;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.util.SlotTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Public entry point for booking use cases. Adds the retry policy around the transactional
 * work: only transient concurrency failures (a version mismatch, or SQLite lock contention) are
 * retried, with a short backoff. Definitive outcomes - slot taken, not found, forbidden - are
 * never retried; they propagate straight to the caller.
 */
@Service
public class BookingServiceImpl implements BookingService {

    static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 25;

    private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

    private final BookingTransactions transactions;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;

    public BookingServiceImpl(BookingTransactions transactions, AppointmentRepository appointmentRepository, Clock clock) {
        this.transactions = transactions;
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
    }

    @Override
    public AppointmentDTO book(long customerId, BookingRequest request) {
        return withRetry(() -> transactions.book(customerId, request));
    }

    @Override
    public void cancel(long customerId, long appointmentId) {
        withRetry(() -> {
            transactions.cancel(customerId, appointmentId);
            return null;
        });
    }

    @Override
    public AppointmentDTO getAppointment(long customerId, long appointmentId) {
        AppointmentRecord record = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (record.customerId() != customerId) {
            throw new ForbiddenException("You can only view your own appointments");
        }
        return appointmentRepository.findDetailById(appointmentId, SlotTime.now(clock))
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
    }

    @Override
    public MyAppointmentsDTO listAppointments(long customerId) {
        List<AppointmentDTO> all = appointmentRepository.findByCustomer(customerId, SlotTime.now(clock));
        List<AppointmentDTO> upcoming = all.stream()
                .filter(a -> "BOOKED".equals(a.status()))
                .toList();
        List<AppointmentDTO> history = all.stream()
                .filter(a -> !"BOOKED".equals(a.status()))
                .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
                .toList();
        return new MyAppointmentsDTO(upcoming, history);
    }

    private <T> T withRetry(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (ConcurrencyFailureException | CannotCreateTransactionException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("Giving up after {} attempts: {}", attempt, e.getMessage());
                    throw new ConflictException("The system is busy right now. Please try again.");
                }
                log.debug("Transient concurrency failure on attempt {}, retrying: {}", attempt, e.getMessage());
                pause(BACKOFF_MILLIS * attempt);
            }
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("The request was interrupted. Please try again.");
        }
    }
}
