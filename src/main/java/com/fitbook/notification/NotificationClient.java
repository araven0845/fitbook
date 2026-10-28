package com.fitbook.notification;

/**
 * Gateway to the notification service. The rest of the app depends only on this interface, so the
 * mock can be swapped for a real channel (Slack/Discord webhook, SMTP) without touching callers.
 */
public interface NotificationClient {

    /** Delivers the notification or throws NotificationDeliveryException once the retry budget is spent. */
    void send(NotificationRequest request);
}
