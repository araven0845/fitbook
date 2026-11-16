package com.fitbook.ai.reminder;

import com.fitbook.ai.AiAvailability;
import com.fitbook.ai.AiConfig;
import com.fitbook.ai.AiProperties;
import com.fitbook.ai.knowledge.KnowledgeBase;
import com.fitbook.dto.ReminderCandidate;
import com.fitbook.notification.NotificationService;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.util.SlotTime;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Agent 2, the autonomous agent. Nobody prompts it: a timer (ReminderSchedule) wakes it up. Each
 * run it (1) scans for booked appointments starting soon that have not been reminded, (2) asks
 * Gemini to write a personal reminder for each one, grounded in the studio's policy file, and
 * (3) sends it through the Milestone 3 notification service, which gives it the timeout, retry,
 * idempotency key and fallback for free.
 *
 * <p>The model writes the words but does not get the last say on the facts: if its text does not
 * contain the exact appointment time, or is too long, a fixed template is sent instead.
 */
@Component
public class ReminderAgent {

    static final int MAX_MESSAGE_LENGTH = 500;   // NotificationRequest allows at most 500 characters

    static final String SYSTEM_PROMPT = """
            You write appointment reminders for FitBook, a fitness studio. Write ONE short, warm message in plain \
            text: no markdown, no emojis, no subject line, at most 350 characters. Greet the member by first name. \
            You must mention the service, the trainer, and the appointment time. Copy the WHEN value exactly, \
            character for character. If the policy notes contain something useful for this appointment (what to \
            bring, when to arrive, the cancellation window), add one brief tip from them. Do not invent rules or facts.
            """;

    private static final Logger log = LoggerFactory.getLogger(ReminderAgent.class);
    private static final DateTimeFormatter WHEN_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMM d 'at' h:mm a", Locale.US);

    private final ChatClient chatClient;
    private final KnowledgeBase knowledgeBase;
    private final AppointmentRepository appointmentRepository;
    private final NotificationService notificationService;
    private final AiAvailability availability;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** The timer and the manual trigger must not run at the same moment. */
    private final ReentrantLock running = new ReentrantLock();

    public ReminderAgent(@Qualifier(AiConfig.REMINDER_CLIENT) ChatClient chatClient, KnowledgeBase knowledgeBase,
                         AppointmentRepository appointmentRepository, NotificationService notificationService,
                         AiAvailability availability, AiProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.chatClient = chatClient;
        this.knowledgeBase = knowledgeBase;
        this.appointmentRepository = appointmentRepository;
        this.notificationService = notificationService;
        this.availability = availability;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** One full scan-write-send pass. Never throws: a problem with one reminder does not stop the others. */
    public ReminderRunSummary runOnce(String trigger) {
        if (!availability.isConfigured()) {
            log.debug("Reminder agent skipped ({}): no Gemini API key configured", trigger);
            return ReminderRunSummary.notRun("SKIPPED", trigger);
        }
        if (!running.tryLock()) {
            return ReminderRunSummary.notRun("ALREADY_RUNNING", trigger);
        }
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            List<ReminderCandidate> due = appointmentRepository.findDueForReminder(
                    SlotTime.format(now), SlotTime.format(now.plus(properties.reminder().lookahead())),
                    properties.reminder().maxPerRun());

            List<ReminderRunSummary.Reminder> results = new ArrayList<>();
            int sent = 0;
            for (ReminderCandidate appointment : due) {
                ReminderRunSummary.Reminder result = remind(appointment);
                results.add(result);
                if (result.delivered()) {
                    sent++;
                }
            }
            log.info("Reminder agent run trigger={} due={} sent={} failed={}", trigger, due.size(), sent, due.size() - sent);
            return new ReminderRunSummary("COMPLETED", trigger, due.size(), sent, due.size() - sent, results);
        } finally {
            running.unlock();
        }
    }

    private ReminderRunSummary.Reminder remind(ReminderCandidate appointment) {
        String when = WHEN_FORMAT.format(LocalDateTime.parse(appointment.startTime(), SlotTime.DB_FORMAT));
        String message = null;
        String writtenBy = "gemini";
        try {
            message = usable(write(appointment, when), when);
        } catch (RuntimeException e) {
            log.warn("Reminder text for appointment {} could not be generated: {}", appointment.appointmentId(), e.getMessage());
        }
        if (message == null) {
            // Fallback: the reminder still goes out, with fixed wording.
            message = "Hi %s, this is a reminder of your %s with %s on %s. See you at FitBook!"
                    .formatted(firstName(appointment.customerName()), appointment.serviceName(), appointment.providerName(), when);
            writtenBy = "template";
        }
        meterRegistry.counter("fitbook.reminder.text", "source", writtenBy).increment();

        boolean delivered = notificationService.notifyReminder(appointment.customerId(), appointment.appointmentId(), message);
        if (delivered) {
            // Only a delivered reminder is recorded, so a failed one is picked up again by the next run.
            appointmentRepository.markReminderSent(appointment.appointmentId(), SlotTime.now(clock));
        }
        meterRegistry.counter("fitbook.reminders", "result", delivered ? "sent" : "failed").increment();
        return new ReminderRunSummary.Reminder(appointment.appointmentId(), appointment.customerName(), when, writtenBy, delivered, message);
    }

    /** The LLM call: appointment facts plus retrieved policy text in, one reminder message out. */
    private String write(ReminderCandidate appointment, String when) {
        String facts = """
                Member: %s
                Service: %s
                Trainer: %s
                WHEN: %s

                Policy notes:
                %s
                """.formatted(appointment.customerName(), appointment.serviceName(), appointment.providerName(), when,
                policyNotes(appointment.serviceName()));
        return chatClient.prompt().system(SYSTEM_PROMPT).user(facts).call().content();
    }

    /** RAG for the reminder: the sections of the policy file closest to this appointment. */
    private String policyNotes(String serviceName) {
        List<Document> sections = knowledgeBase.similaritySearch(SearchRequest.builder()
                .query("What to bring, when to arrive and the cancellation policy for " + serviceName)
                .topK(2)
                .build());
        if (sections.isEmpty()) {
            return "(none available)";
        }
        return sections.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
    }

    /** Accepts the model's text only if it is non-empty, short enough, and states the real appointment time. */
    private static String usable(String generated, String when) {
        if (generated == null) {
            return null;
        }
        String text = generated.strip().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH || !text.contains(when)) {
            log.warn("Generated reminder rejected (empty, too long, or missing the exact time), using the template");
            return null;
        }
        return text;
    }

    private static String firstName(String fullName) {
        String trimmed = fullName == null ? "" : fullName.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
