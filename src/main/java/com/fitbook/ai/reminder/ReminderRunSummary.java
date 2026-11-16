package com.fitbook.ai.reminder;

import java.util.List;

/**
 * What one run of the reminder agent did.
 *
 * @param status    COMPLETED, SKIPPED (no API key) or ALREADY_RUNNING
 * @param trigger   "schedule" or "manual"
 * @param due       appointments that needed a reminder this run
 * @param sent      reminders the notification service accepted
 * @param failed    reminders that could not be delivered (they stay due and are retried next run)
 */
public record ReminderRunSummary(String status, String trigger, int due, int sent, int failed, List<Reminder> reminders) {

    /**
     * @param writtenBy "gemini" when the model's text was used, "template" when the fallback text was used
     */
    public record Reminder(long appointmentId, String customer, String when, String writtenBy, boolean delivered, String message) {}

    static ReminderRunSummary notRun(String status, String trigger) {
        return new ReminderRunSummary(status, trigger, 0, 0, 0, List.of());
    }
}
