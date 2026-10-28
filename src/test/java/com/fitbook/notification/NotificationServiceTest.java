package com.fitbook.notification;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationClient client;
    @Mock private UserRepository userRepository;
    @Mock private AppointmentRepository appointmentRepository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private NotificationService service;

    private final AppointmentDTO appointment = new AppointmentDTO(
            12, 3, "Maya Chen", "Arjun", "1:1 Personal Training", "2026-12-01 09:00", "2026-12-01 10:00", "BOOKED", "now");

    @BeforeEach
    void setUp() {
        service = new NotificationService(client, userRepository, appointmentRepository, meters, Clock.systemDefaultZone());
    }

    private double count(String result) {
        return meters.counter("fitbook.notifications", "result", result).count();
    }

    @Test
    void bookingConfirmation_sendsOneRequestWithAStableIdempotencyKey() {
        when(userRepository.findEmailById(7)).thenReturn(Optional.of("arjun@fitbook.example"));

        service.notifyBookingConfirmed(7, appointment);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).send(sent.capture());
        assertEquals("appointment-12-confirmed", sent.getValue().idempotencyKey());
        assertEquals("arjun@fitbook.example", sent.getValue().recipient());
        assertEquals(NotificationType.BOOKING_CONFIRMED, sent.getValue().type());
        assertEquals(1.0, count("sent"));
    }

    @Test
    void cancellation_usesItsOwnIdempotencyKey() {
        when(appointmentRepository.findDetailById(any(Long.class), anyString())).thenReturn(Optional.of(appointment));
        when(userRepository.findEmailById(7)).thenReturn(Optional.of("arjun@fitbook.example"));

        service.notifyBookingCancelled(7, 12);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).send(sent.capture());
        assertEquals("appointment-12-cancelled", sent.getValue().idempotencyKey());
        assertEquals(NotificationType.BOOKING_CANCELLED, sent.getValue().type());
    }

    @Test
    void fallback_whenDeliveryFails_isSwallowedAndCounted() {
        when(userRepository.findEmailById(7)).thenReturn(Optional.of("arjun@fitbook.example"));
        doThrow(new NotificationDeliveryException("down", new RuntimeException())).when(client).send(any());

        assertDoesNotThrow(() -> service.notifyBookingConfirmed(7, appointment));

        assertEquals(1.0, count("failed"));
        assertEquals(0.0, count("sent"));
    }

    @Test
    void fallback_whenCustomerHasNoEmail_skipsWithoutCallingTheService() {
        when(userRepository.findEmailById(7)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.notifyBookingConfirmed(7, appointment));

        verify(client, never()).send(any());
        assertEquals(1.0, count("failed"));
    }

    @Test
    void fallback_whenEvenLookupsBlowUp_theBookingIsStillUnaffected() {
        when(userRepository.findEmailById(7)).thenThrow(new IllegalStateException("db hiccup"));

        assertDoesNotThrow(() -> service.notifyBookingConfirmed(7, appointment));

        assertEquals(1.0, count("failed"));
    }
}
