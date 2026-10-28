package com.fitbook.notification;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock external notification service: a second controller that stands in for a messaging
 * provider. It accepts a NotificationRequest, stores it, and returns success; it sends nothing
 * for real. Receiving the same idempotencyKey twice stores it once and still answers success, so
 * the caller's retries are safe.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationLogRepository repository;

    public NotificationController(NotificationLogRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public NotificationResponse receive(@Valid @RequestBody NotificationRequest request) {
        boolean stored = repository.insertIfAbsent(request);
        if (stored) {
            log.info("Notification received key={} type={} recipient={}", request.idempotencyKey(), request.type(), request.recipient());
        } else {
            log.info("Duplicate notification ignored key={}", request.idempotencyKey());
        }
        return new NotificationResponse("ACCEPTED", !stored);
    }
}
