package com.fitbook.notification;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.model.SlotStatus;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.util.SlotTime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distribution-boundary guarantee: a booking still succeeds when the notification service is
 * down, slow, or erroring, and the notification is only sent after the booking has committed. A
 * stub HTTP server plays the notification service so each failure mode can be switched on.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./fitbook-resilience-test.db",
        "fitbook.notification.connect-timeout=300ms",
        "fitbook.notification.read-timeout=300ms",
        "fitbook.notification.max-attempts=3",
        "fitbook.notification.backoff=20ms"
})
class NotificationResilienceTest {

    private enum Mode { OK, ERROR_503, SLOW, FAIL_FIRST_THEN_OK }

    private static final Pattern KEY = Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final int PORT = freePort();
    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger();

    private static HttpServer stub;
    private static volatile Mode mode = Mode.OK;
    private static final AtomicInteger requests = new AtomicInteger();
    private static final List<String> receivedKeys = new CopyOnWriteArrayList<>();
    private static volatile Supplier<Boolean> probe;
    private static volatile Boolean probeResult;

    @DynamicPropertySource
    static void notificationUrl(DynamicPropertyRegistry registry) {
        registry.add("fitbook.notification.base-url", () -> "http://localhost:" + PORT);
    }

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;

    private long customer;

    @BeforeEach
    void reset() throws IOException {
        customer = userRepository.findByUsername("member_arjun").orElseThrow().id();
        requests.set(0);
        receivedKeys.clear();
        probe = null;
        probeResult = null;
        mode = Mode.OK;
        startStub();
    }

    @AfterAll
    static void shutDown() {
        stopStub();
    }

    // ---- the required scenarios ----

    @Test
    void bookingStillSucceeds_whenTheNotificationServiceIsDown() {
        stopStub(); // nothing is listening on the port now: connection refused
        double failedBefore = failed();
        long slotId = newOpenSlot();

        long startedAt = System.nanoTime();
        AppointmentDTO booking = bookingService.book(customer, new BookingRequest(slotId, 1L));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals("BOOKED", booking.status());
        assertBookingIsInTheDatabase(slotId);
        assertEquals(failedBefore + 1, failed(), "the failed delivery is counted");
        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "fallback is quick, took " + took);
    }

    @Test
    void bookingStillSucceeds_whenTheNotificationServiceIsSlow() {
        mode = Mode.SLOW; // answers after 1.5 s, but the read timeout is 300 ms
        long slotId = newOpenSlot();

        long startedAt = System.nanoTime();
        AppointmentDTO booking = bookingService.book(customer, new BookingRequest(slotId, 1L));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals("BOOKED", booking.status());
        assertBookingIsInTheDatabase(slotId);
        assertEquals(3, requests.get(), "each of the 3 attempts timed out");
        assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "timeouts cap the wait, took " + took);
    }

    @Test
    void bookingStillSucceeds_whenTheNotificationServiceReturnsErrors() {
        mode = Mode.ERROR_503;
        double failedBefore = failed();
        long slotId = newOpenSlot();

        AppointmentDTO booking = bookingService.book(customer, new BookingRequest(slotId, 1L));

        assertEquals("BOOKED", booking.status());
        assertBookingIsInTheDatabase(slotId);
        assertEquals(3, requests.get());
        assertEquals(failedBefore + 1, failed());
    }

    // ---- idempotent retries and commit-then-notify ----

    @Test
    void retriesReuseTheSameIdempotencyKey_andALaterAttemptCanSucceed() {
        mode = Mode.FAIL_FIRST_THEN_OK;
        double sentBefore = sent();
        double failedBefore = failed();
        long slotId = newOpenSlot();

        AppointmentDTO booking = bookingService.book(customer, new BookingRequest(slotId, 1L));

        assertEquals(List.of("appointment-" + booking.id() + "-confirmed", "appointment-" + booking.id() + "-confirmed"), receivedKeys,
                "both attempts carried the same key");
        assertEquals(sentBefore + 1, sent());
        assertEquals(failedBefore, failed(), "the retry healed the failure");
    }

    @Test
    void theNotificationIsSentOnlyAfterTheBookingHasCommitted() {
        long slotId = newOpenSlot();
        // When the notification request arrives, check from a different connection that the booking is already visible.
        probe = () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE slot_id = ? AND status = 'BOOKED'", Long.class, slotId) == 1;

        bookingService.book(customer, new BookingRequest(slotId, 1L));

        assertEquals(1, requests.get());
        assertEquals(Boolean.TRUE, probeResult, "the appointment was committed before the notification went out");
    }

    // ---- helpers ----

    private void assertBookingIsInTheDatabase(long slotId) {
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = ? AND status = 'BOOKED'", Long.class, slotId));
        assertEquals(SlotStatus.BOOKED, slotRepository.findById(slotId).orElseThrow().status());
    }

    private double sent() {
        return meters.counter("fitbook.notifications", "result", "sent").count();
    }

    private double failed() {
        return meters.counter("fitbook.notifications", "result", "failed").count();
    }

    private long newOpenSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(75).withSecond(0).withNano(0).plusMinutes(SLOT_COUNTER.incrementAndGet());
        return slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized void startStub() throws IOException {
        if (stub != null) {
            return;
        }
        stub = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        stub.setExecutor(Executors.newCachedThreadPool());
        stub.createContext("/notifications", NotificationResilienceTest::handle);
        stub.start();
    }

    private static synchronized void stopStub() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int number = requests.incrementAndGet();
        Matcher matcher = KEY.matcher(body);
        if (matcher.find()) {
            receivedKeys.add(matcher.group(1));
        }
        if (probe != null) {
            probeResult = probe.get();
        }
        try {
            switch (mode) {
                case OK -> reply(exchange, 200);
                case ERROR_503 -> reply(exchange, 503);
                case FAIL_FIRST_THEN_OK -> reply(exchange, number == 1 ? 503 : 200);
                case SLOW -> {
                    Thread.sleep(1500);
                    reply(exchange, 200);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // the client already timed out and hung up
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        byte[] payload = "{\"status\":\"ACCEPTED\",\"duplicate\":false}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }
}
