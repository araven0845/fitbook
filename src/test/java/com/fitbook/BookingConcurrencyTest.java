package com.fitbook;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.exception.ConflictException;
import com.fitbook.model.SlotRecord;
import com.fitbook.model.SlotStatus;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.service.ProviderService;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the double-booking guard against a real SQLite database: several threads try to book the
 * same slot at the same instant and exactly one may succeed. The remaining tests exercise the two
 * extra layers independently - the version check and the unique-index backstop.
 */
@FitbookIntegrationTest
class BookingConcurrencyTest {

    // Seed data: provider 1 is Maya (coach_maya), service 1 is 1:1 Personal Training.
    private static final long PROVIDER_ID = 1;
    private static final long SERVICE_ID = 1;
    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger();

    @Autowired private BookingService bookingService;
    @Autowired private ProviderService providerService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private long customerA;
    private long customerB;

    @BeforeEach
    void lookUpCustomers() {
        customerA = userRepository.findByUsername("member_arjun").orElseThrow().id();
        customerB = userRepository.findByUsername("member_lena").orElseThrow().id();
    }

    /** A fresh OPEN slot far in the future with a start time no other test uses. */
    private long newOpenSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(60).withSecond(0).withNano(0)
                .plusMinutes(SLOT_COUNTER.incrementAndGet());
        return slotRepository.insert(PROVIDER_ID, SERVICE_ID, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
    }

    private long activeAppointments(long slotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = ? AND status <> 'CANCELLED'", Long.class, slotId);
    }

    /** Runs all tasks at the same instant (a latch releases every thread together) and returns their outcomes. */
    private List<Object> raceAll(List<Callable<AppointmentDTO>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<AppointmentDTO> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return (Object) task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }

    private void assertExactlyOneWinner(long slotId, long versionBefore, List<Object> outcomes) {
        long successes = outcomes.stream().filter(o -> o instanceof AppointmentDTO).count();
        List<Object> failures = outcomes.stream().filter(o -> !(o instanceof AppointmentDTO)).toList();

        assertEquals(1, successes, "exactly one booking must succeed, outcomes: " + outcomes);
        failures.forEach(f -> assertInstanceOf(ConflictException.class, f, "losers must get a 409 conflict, got: " + f));
        assertEquals(1, activeAppointments(slotId), "the database must hold exactly one active appointment");
        SlotRecord slot = slotRepository.findById(slotId).orElseThrow();
        assertEquals(SlotStatus.BOOKED, slot.status());
        assertEquals(versionBefore + 1, slot.version(), "the version is bumped exactly once");
    }

    @RepeatedTest(10)
    void twoConcurrentBookingsOnTheSameSlot_exactlyOneSucceeds() throws Exception {
        long slotId = newOpenSlot();
        long versionBefore = slotRepository.findById(slotId).orElseThrow().version();
        BookingRequest request = new BookingRequest(slotId, SERVICE_ID);

        List<Object> outcomes = raceAll(List.of(
                () -> bookingService.book(customerA, request),
                () -> bookingService.book(customerB, request)));

        assertExactlyOneWinner(slotId, versionBefore, outcomes);
    }

    @Test
    void eightConcurrentBookingsOnTheSameSlot_exactlyOneSucceeds() throws Exception {
        long slotId = newOpenSlot();
        long versionBefore = slotRepository.findById(slotId).orElseThrow().version();
        BookingRequest request = new BookingRequest(slotId, SERVICE_ID);

        List<Callable<AppointmentDTO>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long customer = i % 2 == 0 ? customerA : customerB;
            tasks.add(() -> bookingService.book(customer, request));
        }

        assertExactlyOneWinner(slotId, versionBefore, raceAll(tasks));
    }

    @Test
    void staleVersion_bookingUpdateAffectsNoRowsAndLeavesSlotOpen() {
        long slotId = newOpenSlot();
        long currentVersion = slotRepository.findById(slotId).orElseThrow().version();

        assertEquals(0, slotRepository.markBooked(slotId, currentVersion + 5), "an out-of-date version must not match");
        assertEquals(SlotStatus.OPEN, slotRepository.findById(slotId).orElseThrow().status());

        assertEquals(1, slotRepository.markBooked(slotId, currentVersion), "the current version matches");
        assertEquals(0, slotRepository.markBooked(slotId, currentVersion), "re-using the old version no longer matches");
    }

    @Test
    void uniqueIndexBlocksASecondActiveAppointment_evenWhenTheVersionCheckIsBypassed() {
        long slotId = newOpenSlot();
        appointmentRepository.insert(slotId, customerA, SERVICE_ID);

        // Insert directly through the repository: no status check, no version check - only the database can stop this.
        assertThrows(DuplicateKeyException.class, () -> appointmentRepository.insert(slotId, customerB, SERVICE_ID));
        assertEquals(1, activeAppointments(slotId));
    }

    @Test
    void cancellingFreesTheSlotForANewBooking() {
        long slotId = newOpenSlot();
        BookingRequest request = new BookingRequest(slotId, SERVICE_ID);

        AppointmentDTO first = bookingService.book(customerA, request);
        assertThrows(ConflictException.class, () -> bookingService.book(customerB, request));

        bookingService.cancel(customerA, first.id());
        assertEquals(SlotStatus.OPEN, slotRepository.findById(slotId).orElseThrow().status());

        AppointmentDTO second = bookingService.book(customerB, request);
        assertEquals("BOOKED", second.status());
        // the cancelled row is kept as history, and only one appointment is active
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = ?", Long.class, slotId));
        assertEquals(1, activeAppointments(slotId));
    }

    @RepeatedTest(5)
    void bookingRacingProviderRemoval_endsInAConsistentState() throws Exception {
        long slotId = newOpenSlot();
        long mayaUserId = userRepository.findByUsername("coach_maya").orElseThrow().id();

        List<Object> outcomes = raceAll(List.of(
                () -> bookingService.book(customerA, new BookingRequest(slotId, SERVICE_ID)),
                () -> {
                    providerService.removeSlot(mayaUserId, slotId);
                    return null;
                }));

        SlotRecord slot = slotRepository.findById(slotId).orElseThrow();
        long booked = outcomes.stream().filter(o -> o instanceof AppointmentDTO).count();
        long removed = outcomes.stream().filter(o -> o == null).count();
        assertEquals(1, booked + removed, "exactly one of booking/removal wins, outcomes: " + outcomes);
        if (booked == 1) {
            assertEquals(SlotStatus.BOOKED, slot.status());
            assertEquals(1, activeAppointments(slotId));
        } else {
            assertEquals(SlotStatus.CANCELLED, slot.status());
            assertEquals(0, activeAppointments(slotId));
        }
    }
}
