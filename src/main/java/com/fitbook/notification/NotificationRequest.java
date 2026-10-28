package com.fitbook.notification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The single coarse-grained message that crosses the distribution boundary: everything the
 * notification service needs to deliver one message, in one call. The idempotencyKey is reused on
 * every retry of the same notification so the receiver can drop duplicates.
 */
public record NotificationRequest(
        @NotBlank(message = "idempotencyKey is required") String idempotencyKey,
        @NotBlank(message = "recipient is required") @Email(message = "recipient must be an email address") String recipient,
        @NotNull(message = "type is required") NotificationType type,
        @NotBlank(message = "message is required") @Size(max = 500, message = "message must be at most 500 characters") String message
) {}
