package com.fitbook.notification;

/** Storage for the mock notification service (what a real messaging provider would keep on its side). */
public interface NotificationLogRepository {

    /** Stores the notification unless its idempotencyKey was already received. Returns false for a duplicate. */
    boolean insertIfAbsent(NotificationRequest request);
}
