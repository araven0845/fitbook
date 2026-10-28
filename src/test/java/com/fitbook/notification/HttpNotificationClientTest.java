package com.fitbook.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class HttpNotificationClientTest {

    private static final String URL = "http://notify.test/notifications";

    private MockRestServiceServer server;
    private HttpNotificationClient client;

    private final NotificationRequest request =
            new NotificationRequest("appointment-9-confirmed", "a@b.example", NotificationType.BOOKING_CONFIRMED, "hello");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotificationProperties props = new NotificationProperties("http://notify.test", Duration.ofMillis(100), Duration.ofMillis(100), 3, Duration.ofMillis(1));
        client = new HttpNotificationClient(builder.build(), props, new MockEnvironment());
    }

    @Test
    void send_success_makesOneCall() {
        server.expect(times(1), requestTo(URL)).andExpect(method(POST)).andRespond(withSuccess());

        assertDoesNotThrow(() -> client.send(request));

        server.verify();
    }

    @Test
    void send_retriesServerErrors_andEveryAttemptCarriesTheSameIdempotencyKey() {
        server.expect(requestTo(URL)).andExpect(jsonPath("$.idempotencyKey").value("appointment-9-confirmed")).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(jsonPath("$.idempotencyKey").value("appointment-9-confirmed")).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(jsonPath("$.idempotencyKey").value("appointment-9-confirmed")).andRespond(withSuccess());

        assertDoesNotThrow(() -> client.send(request));

        server.verify();
    }

    @Test
    void send_givesUpAfterMaxAttempts() {
        server.expect(times(3), requestTo(URL)).andRespond(withServerError());

        assertThrows(NotificationDeliveryException.class, () -> client.send(request));

        server.verify();
    }

    @Test
    void send_doesNotRetryARejectedRequest() {
        server.expect(times(1), requestTo(URL)).andRespond(withBadRequest());

        assertThrows(NotificationDeliveryException.class, () -> client.send(request));

        server.verify();
    }
}
