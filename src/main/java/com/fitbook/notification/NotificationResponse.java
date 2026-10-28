package com.fitbook.notification;

/** `duplicate` is true when the idempotencyKey had already been received and nothing new was stored. */
public record NotificationResponse(String status, boolean duplicate) {}
