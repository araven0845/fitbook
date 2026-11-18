package com.fitbook.ai;

import com.fitbook.ai.reminder.ReminderAgent;
import com.fitbook.ai.reminder.ReminderRunSummary;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.testsupport.ScriptedChatModel;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 2 with real HTTP all the way: scan SQLite, generate the text (scripted model), and deliver
 * through the Milestone 3 gateway to the mock notification service on the app's own random port.
 * Same context settings as NotificationEndToEndTest, so the two share one running app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./target/fitbook-e2e-test-${random.uuid}.db")
class ReminderEndToEndTest {

    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger();

    @Autowired private ReminderAgent reminderAgent;
    @Autowired private ScriptedChatModel model;
    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private long arjun;

    @BeforeEach
    void setUp() {
        arjun = userRepository.findByUsername("member_arjun").orElseThrow().id();
        model.reset();
    }

    /** Books Arjun into a fresh 1:1 Personal Training slot with Maya that starts the given time from now. */
    private AppointmentDTO bookStartingIn(long hours) {
        LocalDateTime start = LocalDateTime.now().plusHours(hours).withSecond(0).withNano(0)
                .plusMinutes(SLOT_COUNTER.incrementAndGet());
        long slotId = slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
        return bookingService.book(arjun, new BookingRequest(slotId, 1L));
    }

    /** A well-behaved model: it copies the WHEN line it was given into its message. */
    private void scriptAGoodReminder() {
        model.reply(prompt -> {
            String facts = prompt.getUserMessage().getText();
            String when = facts.lines().filter(l -> l.startsWith("WHEN: ")).findFirst().orElseThrow().substring(6);
            return "Hi Arjun, a quick reminder of your 1:1 Personal Training with Maya Chen on " + when + ". Bring water and a towel.";
        });
    }

    private ReminderRunSummary.Reminder reminderFor(ReminderRunSummary summary, long appointmentId) {
        return summary.reminders().stream().filter(r -> r.appointmentId() == appointmentId).findFirst().orElse(null);
    }

    @Test
    void anAppointmentStartingSoon_getsOneReminder_deliveredThroughTheNotificationService() {
        AppointmentDTO appointment = bookStartingIn(3);
        scriptAGoodReminder();

        ReminderRunSummary first = reminderAgent.runOnce("manual");

        ReminderRunSummary.Reminder reminder = reminderFor(first, appointment.id());
        assertNotNull(reminder, "the appointment starting in 3 hours is due: " + first);
        assertTrue(reminder.delivered());
        assertEquals("gemini", reminder.writtenBy());

        // The notification service (the other side of the HTTP boundary) stored exactly this message.
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT recipient, type, message FROM notifications WHERE idempotency_key = ?",
                "appointment-" + appointment.id() + "-reminder");
        assertEquals("member.arjun@fitbook.example", stored.get("recipient"));
        assertEquals("REMINDER", stored.get("type"));
        assertEquals(reminder.message(), stored.get("message"));
        assertNotNull(jdbc.queryForObject("SELECT reminder_sent_at FROM appointments WHERE id = ?", String.class, appointment.id()));

        // A second run finds it already reminded: no second model call for it, no second notification.
        scriptAGoodReminder();
        ReminderRunSummary second = reminderAgent.runOnce("manual");

        assertNull(reminderFor(second, appointment.id()), "already reminded, so no longer due");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE idempotency_key = ?",
                Long.class, "appointment-" + appointment.id() + "-reminder"));
    }

    @Test
    void appointmentsFarInTheFuture_orCancelled_areNotReminded() {
        AppointmentDTO nextWeek = bookStartingIn(24 * 7);
        AppointmentDTO cancelled = bookStartingIn(4);
        bookingService.cancel(arjun, cancelled.id());
        scriptAGoodReminder();

        ReminderRunSummary summary = reminderAgent.runOnce("manual");

        assertNull(reminderFor(summary, nextWeek.id()), "outside the 24 hour window");
        assertNull(reminderFor(summary, cancelled.id()), "cancelled appointments get no reminder");
    }
}
