package com.fitbook.notification;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.util.SlotTime;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * The booking-facing side of the boundary. Called only AFTER the booking or cancellation has
 * committed (commit first, then notify), so a slow or dead notification service can never undo or
 * block a booking. This is also the fallback: if delivery fails after the gateway's retries, we
 * log a warning, count it, and carry on - the booking stays confirmed.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationClient client;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public NotificationService(NotificationClient client, UserRepository userRepository,
                               AppointmentRepository appointmentRepository, MeterRegistry meterRegistry, Clock clock) {
        this.client = client;
        this.userRepository = userRepository;
        this.appointmentRepository = appointmentRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public void notifyBookingConfirmed(long customerId, AppointmentDTO appointment) {
        String message = "Your %s with %s on %s is confirmed (appointment #%d)."
                .formatted(appointment.serviceName(), appointment.providerName(), appointment.startTime(), appointment.id());
        deliver(customerId, NotificationType.BOOKING_CONFIRMED, "appointment-" + appointment.id() + "-confirmed", message);
    }

    public void notifyBookingCancelled(long customerId, long appointmentId) {
        try {
            AppointmentDTO appointment = appointmentRepository.findDetailById(appointmentId, SlotTime.now(clock)).orElse(null);
            if (appointment == null) {
                log.warn("Cancellation notice skipped, appointment {} not found", appointmentId);
                return;
            }
            String message = "Your %s with %s on %s has been cancelled (appointment #%d)."
                    .formatted(appointment.serviceName(), appointment.providerName(), appointment.startTime(), appointment.id());
            deliver(customerId, NotificationType.BOOKING_CANCELLED, "appointment-" + appointmentId + "-cancelled", message);
        } catch (RuntimeException e) {
            countResult("failed");
            log.warn("Cancellation notice for appointment {} could not be prepared, booking state is unaffected: {}", appointmentId, e.getMessage());
        }
    }

    private void deliver(long customerId, NotificationType type, String idempotencyKey, String message) {
        try {
            String recipient = userRepository.findEmailById(customerId).orElse(null);
            if (recipient == null) {
                countResult("failed");
                log.warn("Notification {} skipped, no email for customer {}", idempotencyKey, customerId);
                return;
            }
            client.send(new NotificationRequest(idempotencyKey, recipient, type, message));
            countResult("sent");
            log.info("Notification sent key={} type={} customerId={}", idempotencyKey, type, customerId);
        } catch (RuntimeException e) {
            countResult("failed");
            log.warn("Notification {} not delivered, booking is unaffected: {}", idempotencyKey, e.getMessage());
        }
    }

    private void countResult(String result) {
        meterRegistry.counter("fitbook.notifications", "result", result).increment();
    }
}
