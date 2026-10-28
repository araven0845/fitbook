package com.fitbook.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param baseUrl        where the notification service lives; blank means "this same app" (the mock is in-process)
 * @param connectTimeout how long to wait to open the connection
 * @param readTimeout    how long to wait for the response once connected
 * @param maxAttempts    total tries per notification, including the first
 * @param backoff        base pause between attempts (multiplied by the attempt number)
 */
@ConfigurationProperties(prefix = "fitbook.notification")
public record NotificationProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("1500ms") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("100ms") Duration backoff
) {}
