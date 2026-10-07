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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingTransactionsTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    // "now" is fixed at 2026-10-07 12:00 local time.
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 7, 12, 0).atZone(ZONE).toInstant(), ZONE);
    private static final String FUTURE_START = "2026-10-08 09:00";
    private static final String PAST_START = "2026-10-07 09:00";

    private static final long SLOT_ID = 10;
    private static final long SERVICE_ID = 3;
    private static final long CUSTOMER_ID = 7;

    @Mock
    private SlotRepository slotRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    private BookingTransactions transactions;

    @BeforeEach
    void setUp() {
        transactions = new BookingTransactions(slotRepository, appointmentRepository, CLOCK);
    }

    private SlotRecord slot(SlotStatus status, long version, String start) {
        return new SlotRecord(SLOT_ID, 1, SERVICE_ID, start, "2026-10-08 10:00", status, version);
    }

    private AppointmentDTO dto(long id) {
        return new AppointmentDTO(id, SLOT_ID, "Trainer", "Customer", "Service", FUTURE_START, "2026-10-08 10:00", "BOOKED", "now");
    }

    private BookingRequest request() {
        return new BookingRequest(SLOT_ID, SERVICE_ID);
    }

    // ---- booking rules ----

    @Test
    void book_openSlot_marksSlotBookedThenInsertsAppointment() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.OPEN, 4, FUTURE_START)));
        when(slotRepository.markBooked(SLOT_ID, 4)).thenReturn(1);
        when(appointmentRepository.insert(SLOT_ID, CUSTOMER_ID, SERVICE_ID)).thenReturn(55L);
        when(appointmentRepository.findDetailById(eq(55L), anyString())).thenReturn(Optional.of(dto(55)));

        AppointmentDTO result = transactions.book(CUSTOMER_ID, request());

        assertEquals(55, result.id());
        InOrder order = inOrder(slotRepository, appointmentRepository);
        order.verify(slotRepository).markBooked(SLOT_ID, 4);
        order.verify(appointmentRepository).insert(SLOT_ID, CUSTOMER_ID, SERVICE_ID);
    }

    @Test
    void book_unknownSlot_throwsNotFound() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> transactions.book(CUSTOMER_ID, request()));
        verify(slotRepository, never()).markBooked(anyLong(), anyLong());
    }

    @Test
    void book_serviceDoesNotMatchSlot_throwsBadRequestAndChangesNothing() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.OPEN, 0, FUTURE_START)));

        assertThrows(BadRequestException.class, () -> transactions.book(CUSTOMER_ID, new BookingRequest(SLOT_ID, 99L)));
        verify(slotRepository, never()).markBooked(anyLong(), anyLong());
        verify(appointmentRepository, never()).insert(anyLong(), anyLong(), anyLong());
    }

    @Test
    void book_slotAlreadyBooked_throwsConflict() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.BOOKED, 1, FUTURE_START)));

        assertThrows(ConflictException.class, () -> transactions.book(CUSTOMER_ID, request()));
        verify(slotRepository, never()).markBooked(anyLong(), anyLong());
    }

    @Test
    void book_slotRemovedByProvider_throwsConflict() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.CANCELLED, 1, FUTURE_START)));

        assertThrows(ConflictException.class, () -> transactions.book(CUSTOMER_ID, request()));
    }

    @Test
    void book_slotAlreadyStarted_throwsConflict() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.OPEN, 0, PAST_START)));

        assertThrows(ConflictException.class, () -> transactions.book(CUSTOMER_ID, request()));
        verify(slotRepository, never()).markBooked(anyLong(), anyLong());
    }

    @Test
    void book_versionMovedSinceRead_failsWithRetryableOptimisticLockAndInsertsNothing() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.OPEN, 4, FUTURE_START)));
        when(slotRepository.markBooked(SLOT_ID, 4)).thenReturn(0);

        assertThrows(OptimisticLockingFailureException.class, () -> transactions.book(CUSTOMER_ID, request()));
        verify(appointmentRepository, never()).insert(anyLong(), anyLong(), anyLong());
    }

    @Test
    void book_uniqueIndexRejectsInsert_becomesConflict() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.OPEN, 0, FUTURE_START)));
        when(slotRepository.markBooked(SLOT_ID, 0)).thenReturn(1);
        when(appointmentRepository.insert(SLOT_ID, CUSTOMER_ID, SERVICE_ID)).thenThrow(new DuplicateKeyException("unique"));

        assertThrows(ConflictException.class, () -> transactions.book(CUSTOMER_ID, request()));
    }

    // ---- cancel rules (owner-only) ----

    private AppointmentRecord appointment(long customerId, AppointmentStatus status) {
        return new AppointmentRecord(55, SLOT_ID, customerId, SERVICE_ID, status);
    }

    @Test
    void cancel_owner_cancelsAppointmentAndReopensSlot() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(appointment(CUSTOMER_ID, AppointmentStatus.BOOKED)));
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.BOOKED, 1, FUTURE_START)));
        when(appointmentRepository.markCancelled(55)).thenReturn(1);
        when(slotRepository.reopen(SLOT_ID)).thenReturn(1);

        transactions.cancel(CUSTOMER_ID, 55);

        InOrder order = inOrder(appointmentRepository, slotRepository);
        order.verify(appointmentRepository).markCancelled(55);
        order.verify(slotRepository).reopen(SLOT_ID);
    }

    @Test
    void cancel_someoneElsesAppointment_throwsForbiddenAndChangesNothing() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(appointment(CUSTOMER_ID, AppointmentStatus.BOOKED)));

        assertThrows(ForbiddenException.class, () -> transactions.cancel(CUSTOMER_ID + 1, 55));
        verify(appointmentRepository, never()).markCancelled(anyLong());
        verify(slotRepository, never()).reopen(anyLong());
    }

    @Test
    void cancel_unknownAppointment_throwsNotFound() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> transactions.cancel(CUSTOMER_ID, 55));
    }

    @Test
    void cancel_alreadyCancelled_throwsConflict() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(appointment(CUSTOMER_ID, AppointmentStatus.CANCELLED)));

        assertThrows(ConflictException.class, () -> transactions.cancel(CUSTOMER_ID, 55));
        verify(slotRepository, never()).reopen(anyLong());
    }

    @Test
    void cancel_appointmentAlreadyStarted_throwsConflict() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(appointment(CUSTOMER_ID, AppointmentStatus.BOOKED)));
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.BOOKED, 1, PAST_START)));

        assertThrows(ConflictException.class, () -> transactions.cancel(CUSTOMER_ID, 55));
        verify(appointmentRepository, never()).markCancelled(anyLong());
    }
}
