package com.fitbook.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP implementation of the gateway. Every call has a connect and read timeout (set on the
 * RestClient, see AppConfig). Failures that can heal - I/O errors, timeouts, 5xx - are retried with
 * a growing pause; a 4xx means the request itself is wrong, so it is not retried. Every attempt
 * carries the same idempotencyKey, so a retry after a lost response cannot create a duplicate.
 */
@Component
public class HttpNotificationClient implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpNotificationClient.class);

    private final RestClient restClient;
    private final NotificationProperties properties;
    private final Environment environment;

    public HttpNotificationClient(RestClient notificationRestClient, NotificationProperties properties, Environment environment) {
        this.restClient = notificationRestClient;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void send(NotificationRequest request) {
        int maxAttempts = Math.max(1, properties.maxAttempts());
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restClient.post()
                        .uri(baseUrl() + "/notifications")
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (HttpClientErrorException e) {
                throw new NotificationDeliveryException("Notification service rejected " + request.idempotencyKey() + ": " + e.getStatusCode(), e);
            } catch (RestClientException e) {
                lastFailure = e;
                log.warn("Notification attempt {}/{} failed key={} reason={}", attempt, maxAttempts, request.idempotencyKey(), e.getMessage());
                if (attempt < maxAttempts) {
                    pause(properties.backoff().toMillis() * attempt);
                }
            }
        }
        throw new NotificationDeliveryException("Notification " + request.idempotencyKey() + " not delivered after " + maxAttempts + " attempts", lastFailure);
    }

    private String baseUrl() {
        String configured = properties.baseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException("Interrupted while waiting to retry a notification", e);
        }
    }
}
