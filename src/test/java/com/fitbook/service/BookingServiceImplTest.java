package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.MyAppointmentsDTO;
import com.fitbook.exception.ConflictException;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.model.AppointmentRecord;
import com.fitbook.model.AppointmentStatus;
import com.fitbook.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 7, 12, 0).atZone(ZONE).toInstant(), ZONE);

    @Mock
    private BookingTransactions transactions;
    @Mock
    private AppointmentRepository appointmentRepository;

    private BookingServiceImpl service;

    private final BookingRequest request = new BookingRequest(10L, 3L);

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl(transactions, appointmentRepository, CLOCK);
    }

    private AppointmentDTO dto(long id, String status, String start) {
        return new AppointmentDTO(id, 10, "Trainer", "Customer", "Service", start, "2099-01-01 10:00", status, "now");
    }

    // ---- retry policy ----

    @Test
    void book_retriesTransientFailureThenSucceeds() {
        AppointmentDTO ok = dto(1, "BOOKED", "2026-10-08 09:00");
        when(transactions.book(7, request))
                .thenThrow(new OptimisticLockingFailureException("version moved"))
                .thenThrow(new CannotAcquireLockException("busy"))
                .thenReturn(ok);

        assertEquals(ok, service.book(7, request));
        verify(transactions, times(3)).book(7, request);
    }

    @Test
    void book_givesUpAfterMaxAttemptsWithConflict() {
        when(transactions.book(7, request)).thenThrow(new CannotAcquireLockException("busy"));

        assertThrows(ConflictException.class, () -> service.book(7, request));
        verify(transactions, times(BookingServiceImpl.MAX_ATTEMPTS)).book(7, request);
    }

    @Test
    void book_doesNotRetryDefinitiveConflict() {
        when(transactions.book(7, request)).thenThrow(new ConflictException("This slot is no longer available"));

        assertThrows(ConflictException.class, () -> service.book(7, request));
        verify(transactions, times(1)).book(7, request);
    }

    @Test
    void cancel_retriesTransientFailureThenSucceeds() {
        doThrow(new CannotAcquireLockException("busy")).doNothing().when(transactions).cancel(7, 55);

        service.cancel(7, 55);

        verify(transactions, times(2)).cancel(7, 55);
    }

    @Test
    void cancel_doesNotRetryForbidden() {
        doThrow(new ForbiddenException("not yours")).when(transactions).cancel(7, 55);

        assertThrows(ForbiddenException.class, () -> service.cancel(7, 55));
        verify(transactions, times(1)).cancel(7, 55);
    }

    // ---- reads ----

    @Test
    void getAppointment_ownerSeesIt() {
        AppointmentDTO dto = dto(55, "BOOKED", "2026-10-08 09:00");
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(new AppointmentRecord(55, 10, 7, 3, AppointmentStatus.BOOKED)));
        when(appointmentRepository.findDetailById(anyLong(), anyString())).thenReturn(Optional.of(dto));

        assertEquals(dto, service.getAppointment(7, 55));
    }

    @Test
    void getAppointment_otherCustomer_throwsForbidden() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.of(new AppointmentRecord(55, 10, 7, 3, AppointmentStatus.BOOKED)));

        assertThrows(ForbiddenException.class, () -> service.getAppointment(8, 55));
        verify(appointmentRepository, never()).findDetailById(anyLong(), any());
    }

    @Test
    void getAppointment_unknown_throwsNotFound() {
        when(appointmentRepository.findById(55)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getAppointment(7, 55));
    }

    @Test
    void listAppointments_splitsUpcomingFromHistoryNewestFirst() {
        AppointmentDTO upcoming = dto(1, "BOOKED", "2026-10-09 09:00");
        AppointmentDTO oldCompleted = dto(2, "COMPLETED", "2026-10-01 09:00");
        AppointmentDTO recentCancelled = dto(3, "CANCELLED", "2026-10-05 09:00");
        when(appointmentRepository.findByCustomer(anyLong(), anyString()))
                .thenReturn(List.of(oldCompleted, recentCancelled, upcoming));

        MyAppointmentsDTO result = service.listAppointments(7);

        assertEquals(List.of(upcoming), result.upcoming());
        assertEquals(List.of(recentCancelled, oldCompleted), result.history());
    }
}
