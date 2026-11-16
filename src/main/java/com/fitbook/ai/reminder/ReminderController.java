package com.fitbook.ai.reminder;

import com.fitbook.model.Role;
import com.fitbook.security.RequiresRole;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the reminder agent, so a run can be shown on demand instead of waiting for
 * the timer. Staff-only (PROVIDER role): a run sends real notifications and uses API quota.
 */
@RestController
@RequiresRole(Role.PROVIDER)
public class ReminderController {

    private final ReminderAgent reminderAgent;

    public ReminderController(ReminderAgent reminderAgent) {
        this.reminderAgent = reminderAgent;
    }

    @PostMapping("/agent/reminders/run")
    public ReminderRunSummary runNow() {
        return reminderAgent.runOnce("manual");
    }
}
