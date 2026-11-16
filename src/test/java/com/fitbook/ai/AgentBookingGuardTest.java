package com.fitbook.ai;

import com.fitbook.FitbookIntegrationTest;
import com.fitbook.ai.assistant.BookingTools;
import com.fitbook.ai.assistant.PendingActionStore;
import com.fitbook.ai.assistant.ToolResults.ActionResult;
import com.fitbook.ai.assistant.ToolResults.Status;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.exception.ConflictException;
import com.fitbook.model.SlotRecord;
import com.fitbook.model.SlotStatus;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Milestone 4 required test: when the AGENT books, the booking still goes through the
 * transactional service path, so double-booking is still blocked.
 *
 * <p>These tests call the real tool beans (the exact methods Gemini's tool calls are dispatched
 * to) against a real SQLite database. Two members' agents confirm a booking for the same slot at
 * the same instant, and the database must end up with exactly one appointment.
 */
@FitbookIntegrationTest
class AgentBookingGuardTest {

    // Seed data: provider 1 is Maya, service 1 is 1:1 Personal Training.
    private static final long PROVIDER_ID = 1;
    private static final long SERVICE_ID = 1;
    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger();
    private static final AtomicInteger TURN = new AtomicInteger(1000);

    @Autowired private BookingTools tools;
    @Autowired private PendingActionStore pendingActions;
    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private long arjun;
    private long lena;

    @BeforeEach
    void lookUpMembers() {
        arjun = userRepository.findByUsername("member_arjun").orElseThrow().id();
        lena = userRepository.findByUsername("member_lena").orElseThrow().id();
        pendingActions.discard(arjun);
        pendingActions.discard(lena);
    }

    private long newOpenSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(70).withSecond(0).withNano(0)
                .plusMinutes(SLOT_COUNTER.incrementAndGet());
        return slotRepository.insert(PROVIDER_ID, SERVICE_ID, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
    }

    /** The tool context the chat service builds for one turn of one logged-in member. */
    private static ToolContext turnOf(long userId) {
        return new ToolContext(Map.of(BookingTools.USER_ID, userId, BookingTools.TURN, (long) TURN.incrementAndGet()));
    }

    private long activeAppointments(long slotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = ? AND status <> 'CANCELLED'", Long.class, slotId);
    }

    /** Releases every task at the same instant and collects what each returned (or threw). */
    private List<Object> raceAll(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }

    @RepeatedTest(10)
    void twoAgentsConfirmABookingForTheSameSlotAtTheSameInstant_exactlyOneIsBooked() throws Exception {
        long slotId = newOpenSlot();
        long versionBefore = slotRepository.findById(slotId).orElseThrow().version();

        // Turn 1 for each member: the agent prepares the booking. Both see the slot as open.
        assertEquals(Status.NEEDS_CONFIRMATION, tools.prepareBooking(slotId, turnOf(arjun)).status());
        assertEquals(Status.NEEDS_CONFIRMATION, tools.prepareBooking(slotId, turnOf(lena)).status());
        assertEquals(0, activeAppointments(slotId), "preparing must not write anything");

        // Turn 2 for each member: both say yes, and both agents call confirm at the same moment.
        List<Object> outcomes = raceAll(List.of(
                () -> tools.confirmPendingAction(turnOf(arjun)),
                () -> tools.confirmPendingAction(turnOf(lena))));

        List<Status> statuses = outcomes.stream().map(o -> assertInstanceOf(ActionResult.class, o).status()).toList();
        assertEquals(1, statuses.stream().filter(s -> s == Status.BOOKED).count(), "exactly one agent may book: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == Status.SLOT_TAKEN).count(), "the other must be told the slot was taken: " + statuses);

        assertEquals(1, activeAppointments(slotId), "the database must hold exactly one active appointment");
        SlotRecord slot = slotRepository.findById(slotId).orElseThrow();
        assertEquals(SlotStatus.BOOKED, slot.status());
        assertEquals(versionBefore + 1, slot.version(), "the optimistic-lock version is bumped exactly once");
    }

    @Test
    void anAgentAndTheWebsiteRaceForTheSameSlot_exactlyOneIsBooked() throws Exception {
        long slotId = newOpenSlot();
        tools.prepareBooking(slotId, turnOf(arjun));

        List<Object> outcomes = raceAll(List.of(
                () -> tools.confirmPendingAction(turnOf(arjun)),
                () -> bookingService.book(lena, new BookingRequest(slotId, SERVICE_ID))));

        boolean agentWon = outcomes.get(0) instanceof ActionResult result && result.status() == Status.BOOKED;
        boolean websiteWon = outcomes.get(1) instanceof AppointmentDTO;
        assertTrue(agentWon ^ websiteWon, "exactly one of the two may win: " + outcomes);
        if (agentWon) {
            assertInstanceOf(ConflictException.class, outcomes.get(1), "the website request must get the usual 409");
        } else {
            assertEquals(Status.SLOT_TAKEN, ((ActionResult) outcomes.get(0)).status());
        }
        assertEquals(1, activeAppointments(slotId));
    }

    @Test
    void theAgentCannotBookASlotThatWasTakenAfterItPreparedTheBooking() {
        long slotId = newOpenSlot();
        tools.prepareBooking(slotId, turnOf(arjun));

        // While Arjun is reading the summary, Lena books the slot on the website.
        bookingService.book(lena, new BookingRequest(slotId, SERVICE_ID));

        ActionResult result = tools.confirmPendingAction(turnOf(arjun));

        assertEquals(Status.SLOT_TAKEN, result.status());
        assertEquals(1, activeAppointments(slotId), "Lena's booking is the only one");
        assertEquals(lena, jdbc.queryForObject(
                "SELECT customer_id FROM appointments WHERE slot_id = ? AND status = 'BOOKED'", Long.class, slotId));
        assertFalse(result.alternatives().isEmpty(), "the agent is handed other open slots to offer");
        assertTrue(result.alternatives().stream().noneMatch(s -> s.slotId() == slotId), "and the taken slot is not one of them");
    }

    @Test
    void aBookingMadeByTheAgent_isARealAppointmentTheMemberCanCancel() {
        long slotId = newOpenSlot();
        tools.prepareBooking(slotId, turnOf(arjun));
        ActionResult booked = tools.confirmPendingAction(turnOf(arjun));
        assertEquals(Status.BOOKED, booked.status());
        long appointmentId = booked.appointment().appointmentId();

        // Lena's agent cannot cancel Arjun's appointment: the owner check in the service still applies.
        assertEquals(Status.NOT_FOUND, tools.prepareCancellation(appointmentId, turnOf(lena)).status());
        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(turnOf(lena)).status());
        assertEquals(1, activeAppointments(slotId));

        // Arjun's agent can, in two turns, and the slot opens up again.
        assertEquals(Status.NEEDS_CONFIRMATION, tools.prepareCancellation(appointmentId, turnOf(arjun)).status());
        assertEquals(Status.CANCELLED, tools.confirmPendingAction(turnOf(arjun)).status());
        assertEquals(0, activeAppointments(slotId));
        assertEquals(SlotStatus.OPEN, slotRepository.findById(slotId).orElseThrow().status());
    }
}
