package com.fitbook.notification;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP all the way: the app calls the mock notification controller on its own random port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./fitbook-e2e-test.db")
class NotificationEndToEndTest {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;

    private Map<String, Object> notification(String key) {
        return jdbc.queryForMap("SELECT recipient, type, message FROM notifications WHERE idempotency_key = ?", key);
    }

    @Test
    void bookingAndCancellation_eachTriggerOneNotificationThroughTheMockService() {
        long customer = userRepository.findByUsername("member_arjun").orElseThrow().id();
        LocalDateTime start = LocalDateTime.now().plusDays(80).withSecond(0).withNano(0);
        long slotId = slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));

        AppointmentDTO booking = bookingService.book(customer, new BookingRequest(slotId, 1L));
        Map<String, Object> confirmed = notification("appointment-" + booking.id() + "-confirmed");
        assertEquals("member.arjun@fitbook.example", confirmed.get("recipient"));
        assertEquals("BOOKING_CONFIRMED", confirmed.get("type"));
        assertTrue(confirmed.get("message").toString().contains("confirmed"));

        bookingService.cancel(customer, booking.id());
        Map<String, Object> cancelled = notification("appointment-" + booking.id() + "-cancelled");
        assertEquals("BOOKING_CANCELLED", cancelled.get("type"));
    }

    @Test
    void sendingTheSameNotificationTwice_isStoredOnce() {
        NotificationRequest request = new NotificationRequest(
                "dup-" + UUID.randomUUID(), "x@y.example", NotificationType.BOOKING_CONFIRMED, "hello");

        ResponseEntity<NotificationResponse> first = http.postForEntity("/notifications", request, NotificationResponse.class);
        ResponseEntity<NotificationResponse> second = http.postForEntity("/notifications", request, NotificationResponse.class);

        assertTrue(first.getStatusCode().is2xxSuccessful());
        assertFalse(first.getBody().duplicate());
        assertTrue(second.getStatusCode().is2xxSuccessful());
        assertTrue(second.getBody().duplicate());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE idempotency_key = ?", Long.class, request.idempotencyKey()));
    }
}
