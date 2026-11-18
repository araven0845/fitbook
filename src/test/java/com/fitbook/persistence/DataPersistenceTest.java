package com.fitbook.persistence;

import com.fitbook.FitbookApplication;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Data survives a restart. Starts the whole application on a database file, makes a booking and a
 * cancellation, shuts the application down, starts it again on the same file, and checks that
 * everything is still there and that the demo data was not loaded a second time.
 */
class DataPersistenceTest {

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext start(Path databaseFile) {
        // Passed as command-line arguments so they outrank application.yml (which points at ./fitbook.db).
        return new SpringApplicationBuilder(FitbookApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + databaseFile,
                        "--fitbook.notification.base-url=http://localhost:1",
                        "--fitbook.notification.max-attempts=1",
                        "--spring.jmx.enabled=false");
    }

    private static Map<String, Object> counts(JdbcTemplate jdbc) {
        return jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM users) AS users,
                       (SELECT COUNT(*) FROM services) AS services,
                       (SELECT COUNT(*) FROM availability_slots) AS slots,
                       (SELECT COUNT(*) FROM appointments) AS appointments,
                       (SELECT COUNT(*) FROM chat_memory) AS memory
                """);
    }

    @Test
    void bookingsCancellationsAndConversationsSurviveARestart_andTheDemoDataIsNotLoadedTwice() throws Exception {
        Path databaseFile = tempDir.resolve("restart.db");
        long booked;
        long cancelled;
        Map<String, Object> before;

        // First run: a fresh file gets the schema and the demo data, then we change some data.
        try (ConfigurableApplicationContext first = start(databaseFile)) {
            BookingService bookings = first.getBean(BookingService.class);
            SlotRepository slots = first.getBean(SlotRepository.class);
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            long arjun = first.getBean(UserRepository.class).findByUsername("member_arjun").orElseThrow().id();

            LocalDateTime start = LocalDateTime.now().plusDays(40).withSecond(0).withNano(0);
            long slotA = slots.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
            long slotB = slots.insert(1, 1, SlotTime.format(start.plusHours(2)), SlotTime.format(start.plusHours(2).plusMinutes(30)));
            booked = bookings.book(arjun, new BookingRequest(slotA, 1L)).id();
            cancelled = bookings.book(arjun, new BookingRequest(slotB, 1L)).id();
            bookings.cancel(arjun, cancelled);
            jdbc.update("INSERT INTO chat_memory (conversation_id, position, role, content) VALUES ('user-1', 0, 'USER', 'hello')");

            assertEquals(databaseFile.toRealPath().toString(),
                    jdbc.queryForObject("SELECT file FROM pragma_database_list WHERE name = 'main'", String.class),
                    "the test runs on its own temporary file, never on ./fitbook.db");
            before = counts(jdbc);
            assertEquals(4L, ((Number) before.get("users")).longValue(), "the demo data was loaded into the new file");
        }

        // Second run on the same file: nothing dropped, nothing duplicated.
        try (ConfigurableApplicationContext second = start(databaseFile)) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            BookingService bookings = second.getBean(BookingService.class);
            long arjun = second.getBean(UserRepository.class).findByUsername("member_arjun").orElseThrow().id();

            assertEquals(before, counts(jdbc), "every table has exactly the rows it had before the restart");
            AppointmentDTO stillBooked = bookings.getAppointment(arjun, booked);
            assertEquals("BOOKED", stillBooked.status());
            assertEquals("CANCELLED", bookings.getAppointment(arjun, cancelled).status(), "cancellation history is kept");
        }
    }
}
