package com.fitbook.ai.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer that makes Agent 2 autonomous: Spring calls tick() on its own schedule, with no user
 * request involved. Kept separate from the agent so tests (and the manual trigger) can run the
 * agent directly, and so the timer can be switched off with one property.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fitbook.ai.reminder.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSchedule {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedule.class);

    private final ReminderAgent reminderAgent;

    public ReminderSchedule(ReminderAgent reminderAgent) {
        this.reminderAgent = reminderAgent;
    }

    @Scheduled(initialDelayString = "${fitbook.ai.reminder.initial-delay:1m}",
            fixedDelayString = "${fitbook.ai.reminder.interval:15m}")
    public void tick() {
        try {
            reminderAgent.runOnce("schedule");
        } catch (RuntimeException e) {
            log.warn("Scheduled reminder run failed, will try again at the next interval: {}", e.getMessage());
        }
    }
}
