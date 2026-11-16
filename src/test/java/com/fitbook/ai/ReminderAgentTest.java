package com.fitbook.ai;

import com.fitbook.ai.knowledge.KnowledgeBase;
import com.fitbook.ai.reminder.ReminderAgent;
import com.fitbook.ai.reminder.ReminderRunSummary;
import com.fitbook.dto.ReminderCandidate;
import com.fitbook.notification.NotificationService;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.testsupport.HashingEmbeddingModel;
import com.fitbook.testsupport.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 2's logic with its collaborators mocked: what it scans for, what it asks the model, when
 * it trusts the model's text, and what it does when the model or the notification service fails.
 */
class ReminderAgentTest {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 16, 12, 0).atZone(ZONE).toInstant(), ZONE);
    private static final String WHEN = "Tuesday, Nov 17 at 8:00 AM";
    private static final ReminderCandidate YOGA = new ReminderCandidate(
            99, 7, "Arjun Ravendran", "Vinyasa Yoga", "Diego Alvarez", "2026-11-17 08:00", "2026-11-17 09:00");
    private static final ReminderCandidate HIIT = new ReminderCandidate(
            100, 8, "Lena Park", "Group HIIT Class", "Maya Chen", "2026-11-17 08:00", "2026-11-17 08:45");

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ReminderAgent agent;

    @BeforeEach
    void setUp() {
        agent = agentWith(new AiAvailability("a-key"));
        when(appointments.findDueForReminder(anyString(), anyString(), anyInt())).thenReturn(List.of(YOGA));
        when(notifications.notifyReminder(anyLong(), anyLong(), anyString())).thenReturn(true);
    }

    private ReminderAgent agentWith(AiAvailability availability) {
        AiProperties properties = new AiProperties(Duration.ofSeconds(30), 20, Duration.ofMinutes(10),
                new AiProperties.Rag(3, 0.5),
                new AiProperties.Reminder(false, Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofHours(24), 10));
        KnowledgeBase knowledgeBase = new KnowledgeBase(new HashingEmbeddingModel(),
                new ClassPathResource("knowledge/fitbook-faq.md"), availability, CLOCK);
        return new ReminderAgent(ChatClient.builder(model).build(), knowledgeBase, appointments, notifications,
                availability, properties, meters, CLOCK);
    }

    @Test
    void itScansTheNext24Hours_writesWithTheModel_andSendsThroughTheNotificationService() {
        String written = "Hi Arjun, your Vinyasa Yoga with Diego Alvarez is on " + WHEN + ". Mats are provided, just bring water.";
        model.say(written);

        ReminderRunSummary summary = agent.runOnce("schedule");

        verify(appointments).findDueForReminder("2026-11-16 12:00", "2026-11-17 12:00", 10);
        verify(notifications).notifyReminder(7, 99, written);
        verify(appointments).markReminderSent(99, "2026-11-16 12:00");
        assertEquals("COMPLETED", summary.status());
        assertEquals(1, summary.sent());
        assertEquals(0, summary.failed());
        assertEquals("gemini", summary.reminders().get(0).writtenBy());
        assertEquals(1.0, meters.counter("fitbook.reminders", "result", "sent").count());
    }

    @Test
    void theModelIsGivenTheAppointmentFacts_andPolicyTextRetrievedFromTheFaqFile() {
        model.say("Hi Arjun, see you " + WHEN + ".");

        agent.runOnce("schedule");

        String facts = model.lastPrompt().getUserMessage().getText();
        assertTrue(facts.contains("Member: Arjun Ravendran"), facts);
        assertTrue(facts.contains("Service: Vinyasa Yoga"), facts);
        assertTrue(facts.contains("Trainer: Diego Alvarez"), facts);
        assertTrue(facts.contains("WHEN: " + WHEN), facts);
        assertFalse(facts.contains("(none available)"), "policy notes from the knowledge base are attached: " + facts);
    }

    @Test
    void textThatDoesNotStateTheExactAppointmentTime_isReplacedByTheTemplate() {
        model.say("Hi Arjun, see you tomorrow at 9 for yoga!");   // wrong time: the model is not trusted on facts

        ReminderRunSummary summary = agent.runOnce("schedule");

        String template = "Hi Arjun, this is a reminder of your Vinyasa Yoga with Diego Alvarez on " + WHEN + ". See you at FitBook!";
        verify(notifications).notifyReminder(7, 99, template);
        assertEquals("template", summary.reminders().get(0).writtenBy());
        assertEquals(1, summary.sent());
    }

    @Test
    void textThatIsTooLongForANotification_isReplacedByTheTemplate() {
        model.say("Hi Arjun, see you " + WHEN + ". " + "Please remember to bring water. ".repeat(30));

        ReminderRunSummary summary = agent.runOnce("schedule");

        assertEquals("template", summary.reminders().get(0).writtenBy());
        assertTrue(summary.reminders().get(0).message().length() <= 500);
    }

    @Test
    void ifTheModelIsDown_theReminderStillGoesOut_withTheTemplateText() {
        model.failWith(new IllegalStateException("429 rate limit"));

        ReminderRunSummary summary = agent.runOnce("schedule");

        assertEquals(1, summary.sent());
        assertEquals("template", summary.reminders().get(0).writtenBy());
        verify(appointments).markReminderSent(99, "2026-11-16 12:00");
    }

    @Test
    void ifDeliveryFails_theAppointmentIsNotMarked_soTheNextRunTriesAgain() {
        model.say("Hi Arjun, see you " + WHEN + ".");
        when(notifications.notifyReminder(anyLong(), anyLong(), anyString())).thenReturn(false);

        ReminderRunSummary summary = agent.runOnce("schedule");

        assertEquals(0, summary.sent());
        assertEquals(1, summary.failed());
        assertFalse(summary.reminders().get(0).delivered());
        verify(appointments, never()).markReminderSent(anyLong(), anyString());
        assertEquals(1.0, meters.counter("fitbook.reminders", "result", "failed").count());
    }

    @Test
    void everyDueAppointmentGetsItsOwnReminder() {
        when(appointments.findDueForReminder(anyString(), anyString(), anyInt())).thenReturn(List.of(YOGA, HIIT));
        model.say("Hi Arjun, yoga with Diego Alvarez on " + WHEN + ".")
                .say("Hi Lena, HIIT with Maya Chen on " + WHEN + ".");

        ReminderRunSummary summary = agent.runOnce("manual");

        assertEquals(2, summary.due());
        assertEquals(2, summary.sent());
        verify(notifications).notifyReminder(7, 99, "Hi Arjun, yoga with Diego Alvarez on " + WHEN + ".");
        verify(notifications).notifyReminder(8, 100, "Hi Lena, HIIT with Maya Chen on " + WHEN + ".");
    }

    @Test
    void withNothingDue_theModelIsNotCalled() {
        when(appointments.findDueForReminder(anyString(), anyString(), anyInt())).thenReturn(List.of());

        ReminderRunSummary summary = agent.runOnce("schedule");

        assertEquals("COMPLETED", summary.status());
        assertEquals(0, summary.due());
        assertTrue(model.prompts().isEmpty());
        verifyNoInteractions(notifications);
    }

    @Test
    void withoutAnApiKey_theRunIsSkipped() {
        ReminderRunSummary summary = agentWith(new AiAvailability("")).runOnce("schedule");

        assertEquals("SKIPPED", summary.status());
        verifyNoInteractions(appointments, notifications);
        assertTrue(model.prompts().isEmpty());
    }
}
