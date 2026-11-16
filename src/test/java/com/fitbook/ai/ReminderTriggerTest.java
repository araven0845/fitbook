package com.fitbook.ai;

import com.fitbook.FitbookIntegrationTest;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.SessionUser;
import com.fitbook.model.UserRecord;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.security.SessionKeys;
import com.fitbook.service.BookingService;
import com.fitbook.testsupport.ScriptedChatModel;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The manual trigger endpoint for the reminder agent: who may call it, and what it reports. */
@FitbookIntegrationTest
class ReminderTriggerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ScriptedChatModel model;
    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        model.reset();
    }

    private MockHttpSession sessionOf(String username) {
        UserRecord user = userRepository.findByUsername(username).orElseThrow();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CURRENT_USER, new SessionUser(user.id(), user.username(), user.fullName(), user.role()));
        return session;
    }

    @Test
    void theTrigger_isForStaffOnly() throws Exception {
        mockMvc.perform(post("/agent/reminders/run").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/agent/reminders/run").session(sessionOf("member_arjun")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertTrue(model.prompts().isEmpty(), "a refused request never starts a run");
    }

    @Test
    void aProviderCanRunTheAgentOnDemand_andAnUndeliveredReminderStaysDue() throws Exception {
        long arjun = userRepository.findByUsername("member_arjun").orElseThrow().id();
        LocalDateTime start = LocalDateTime.now().plusHours(5).withSecond(0).withNano(0);
        long slotId = slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
        AppointmentDTO appointment = bookingService.book(arjun, new BookingRequest(slotId, 1L));
        model.reply(prompt -> "Hi Arjun, see you on "
                + prompt.getUserMessage().getText().lines().filter(l -> l.startsWith("WHEN: ")).findFirst().orElseThrow().substring(6) + ".");

        // In this test context the notification service is unreachable (see FitbookIntegrationTest),
        // so the reminder is written but cannot be delivered.
        mockMvc.perform(post("/agent/reminders/run").session(sessionOf("coach_maya")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.trigger").value("manual"))
                .andExpect(jsonPath("$.reminders[?(@.appointmentId == " + appointment.id() + ")].delivered").value(false))
                .andExpect(jsonPath("$.reminders[?(@.appointmentId == " + appointment.id() + ")].writtenBy").value("gemini"));

        assertNull(jdbc.queryForObject("SELECT reminder_sent_at FROM appointments WHERE id = ?", String.class, appointment.id()),
                "not marked as reminded, so the next run will try again");

        bookingService.cancel(arjun, appointment.id());   // leave nothing due behind for other tests
    }
}
