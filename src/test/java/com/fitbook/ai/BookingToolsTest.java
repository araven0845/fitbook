package com.fitbook.ai;

import com.fitbook.ai.assistant.BookingTools;
import com.fitbook.ai.assistant.PendingActionStore;
import com.fitbook.ai.assistant.ToolResults.ActionResult;
import com.fitbook.ai.assistant.ToolResults.AvailabilityResult;
import com.fitbook.ai.assistant.ToolResults.Status;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.PageResult;
import com.fitbook.dto.ServiceDTO;
import com.fitbook.exception.BadRequestException;
import com.fitbook.exception.ConflictException;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.service.BookingService;
import com.fitbook.service.CatalogService;
import com.fitbook.service.SlotService;
import com.fitbook.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agent's tools in isolation, with the service layer mocked. Shows what each tool does and
 * does not call: preparing never writes, confirming calls BookingService with the identity taken
 * from the tool context, and a confirm in the same turn as the prepare is refused.
 */
class BookingToolsTest {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final long ARJUN = 7;
    private static final long LENA = 8;
    private static final long SLOT_ID = 42;
    private static final long SERVICE_ID = 3;

    private final SlotService slotService = mock(SlotService.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final CatalogService catalogService = mock(CatalogService.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 16, 12, 0).atZone(ZONE).toInstant(), ZONE);
    private final PendingActionStore pendingActions = new PendingActionStore(clock);

    private BookingTools tools;

    private static final AvailabilitySlotDTO OPEN_SLOT = new AvailabilitySlotDTO(
            SLOT_ID, 2, "Diego Alvarez", SERVICE_ID, "Vinyasa Yoga", "2026-11-17 08:00", "2026-11-17 09:00", "OPEN");
    private static final AvailabilitySlotDTO OTHER_SLOT = new AvailabilitySlotDTO(
            43, 2, "Diego Alvarez", SERVICE_ID, "Vinyasa Yoga", "2026-11-18 18:00", "2026-11-18 19:00", "OPEN");
    private static final AppointmentDTO APPOINTMENT = new AppointmentDTO(
            99, SLOT_ID, "Diego Alvarez", "Arjun Ravendran", "Vinyasa Yoga", "2026-11-17 08:00", "2026-11-17 09:00", "BOOKED", "2026-11-16 12:00");

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties(Duration.ofSeconds(30), 20, Duration.ofMinutes(10),
                new AiProperties.Rag(3, 0.5),
                new AiProperties.Reminder(false, Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofHours(24), 10));
        tools = new BookingTools(slotService, bookingService, catalogService, pendingActions, properties, new SimpleMeterRegistry(), clock);

        when(slotService.getSlot(SLOT_ID)).thenReturn(OPEN_SLOT);
        when(slotService.browseAvailable(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(OTHER_SLOT), 0, 3, 1));
        when(catalogService.listServices()).thenReturn(List.of(new ServiceDTO(SERVICE_ID, "Vinyasa Yoga", 60, 30.0, "Flow")));
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    /** What AssistantService puts in the tool context: who is logged in, and which chat turn this is. */
    private static ToolContext as(long userId, long turn) {
        return new ToolContext(Map.of(BookingTools.USER_ID, userId, BookingTools.TURN, turn));
    }

    // ---- confirm-before-write ----

    @Test
    void prepareBooking_returnsASummaryAndWritesNothing() {
        ActionResult result = tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        assertEquals(Status.NEEDS_CONFIRMATION, result.status());
        assertTrue(result.summary().contains("Vinyasa Yoga with Diego Alvarez on Tuesday 2026-11-17 08:00 to 09:00"), result.summary());
        assertTrue(result.summary().contains("$30.00"), result.summary());
        assertTrue(pendingActions.find(ARJUN).isPresent(), "the booking waits on the server for the member's yes");
        verifyNoInteractions(bookingService);
    }

    @Test
    void confirmInTheSameTurnAsThePrepare_isRefusedAndNothingIsBooked() {
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        ActionResult result = tools.confirmPendingAction(as(ARJUN, 1));

        assertEquals(Status.NOT_CONFIRMED_YET, result.status());
        verify(bookingService, never()).book(anyLong(), any());
        assertTrue(pendingActions.find(ARJUN).isPresent(), "still waiting, so a real yes in the next turn works");
    }

    @Test
    void confirmInALaterTurn_booksThroughTheBookingService_asTheLoggedInMember() {
        when(bookingService.book(ARJUN, new BookingRequest(SLOT_ID, SERVICE_ID))).thenReturn(APPOINTMENT);
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        ActionResult result = tools.confirmPendingAction(as(ARJUN, 2));

        assertEquals(Status.BOOKED, result.status());
        assertEquals(99, result.appointment().appointmentId());
        // The write is exactly the call the website makes: same service method, id from the session, slot from the prepare.
        verify(bookingService, times(1)).book(ARJUN, new BookingRequest(SLOT_ID, SERVICE_ID));
        assertTrue(pendingActions.find(ARJUN).isEmpty(), "a confirmed action cannot be confirmed twice");
        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(as(ARJUN, 3)).status());
        verify(bookingService, times(1)).book(anyLong(), any());
    }

    @Test
    void preparingTheSameBookingAgainRightBeforeConfirming_stillCountsFromTheFirstSummary() {
        when(bookingService.book(ARJUN, new BookingRequest(SLOT_ID, SERVICE_ID))).thenReturn(APPOINTMENT);
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));          // turn 1: summary shown to the member

        tools.prepareBooking(SLOT_ID, as(ARJUN, 2));          // turn 2: the member said yes, the model re-prepares...
        ActionResult result = tools.confirmPendingAction(as(ARJUN, 2));   // ...and confirms in the same turn

        assertEquals(Status.BOOKED, result.status());
    }

    @Test
    void preparingADifferentSlot_needsItsOwnConfirmationTurn() {
        when(slotService.getSlot(43)).thenReturn(OTHER_SLOT);
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));          // turn 1: summary for slot 42

        tools.prepareBooking(43, as(ARJUN, 2));               // turn 2: the model switches to slot 43...
        ActionResult result = tools.confirmPendingAction(as(ARJUN, 2));   // ...which the member has never been shown

        assertEquals(Status.NOT_CONFIRMED_YET, result.status());
        verifyNoInteractions(bookingService);
    }

    @Test
    void confirm_whenTheSlotWasTakenInTheMeantime_reportsItAndOffersAlternatives() {
        when(bookingService.book(anyLong(), any())).thenThrow(new ConflictException("This slot is no longer available"));
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        ActionResult result = tools.confirmPendingAction(as(ARJUN, 2));

        assertEquals(Status.SLOT_TAKEN, result.status());
        assertEquals(List.of(43L), result.alternatives().stream().map(s -> s.slotId()).toList());
        assertTrue(pendingActions.find(ARJUN).isEmpty());
    }

    @Test
    void confirm_withNothingPrepared_doesNothing() {
        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(as(ARJUN, 5)).status());
        verifyNoInteractions(bookingService);
    }

    @Test
    void aPreparedBookingExpires_ifTheMemberDoesNotConfirmInTime() {
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));
        clock.advance(Duration.ofMinutes(11));

        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(as(ARJUN, 2)).status());
        verifyNoInteractions(bookingService);
    }

    @Test
    void aPreparedBookingWaitsForExactlyOneReply() {
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        pendingActions.dropIfLeftUnconfirmed(ARJUN, 1);     // end of the turn that prepared it: still waiting
        assertTrue(pendingActions.find(ARJUN).isPresent());

        pendingActions.dropIfLeftUnconfirmed(ARJUN, 2);     // end of the next turn, which did not confirm: dropped
        assertTrue(pendingActions.find(ARJUN).isEmpty());
        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(as(ARJUN, 3)).status());
        verifyNoInteractions(bookingService);
    }

    @Test
    void oneMembersPreparedBooking_cannotBeConfirmedByAnotherMember() {
        tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        assertEquals(Status.NOTHING_PENDING, tools.confirmPendingAction(as(LENA, 2)).status());
        verifyNoInteractions(bookingService);
    }

    // ---- taken slots and alternatives ----

    @Test
    void prepareBooking_forASlotThatIsAlreadyBooked_offersAlternativesInstead() {
        when(slotService.getSlot(SLOT_ID)).thenReturn(new AvailabilitySlotDTO(
                SLOT_ID, 2, "Diego Alvarez", SERVICE_ID, "Vinyasa Yoga", "2026-11-17 08:00", "2026-11-17 09:00", "BOOKED"));

        ActionResult result = tools.prepareBooking(SLOT_ID, as(ARJUN, 1));

        assertEquals(Status.UNAVAILABLE, result.status());
        assertEquals(1, result.alternatives().size());
        assertTrue(pendingActions.find(ARJUN).isEmpty());
    }

    @Test
    void prepareBooking_forASlotThatAlreadyStarted_isUnavailable() {
        clock.advance(Duration.ofDays(2));
        assertEquals(Status.UNAVAILABLE, tools.prepareBooking(SLOT_ID, as(ARJUN, 1)).status());
    }

    @Test
    void prepareBooking_forAnUnknownSlot_tellsTheModelToLookItUp() {
        when(slotService.getSlot(777)).thenThrow(new NotFoundException("Slot not found"));
        assertEquals(Status.NOT_FOUND, tools.prepareBooking(777, as(ARJUN, 1)).status());
    }

    // ---- cancelling ----

    @Test
    void cancellation_isAlsoTwoStep_andGoesThroughTheBookingService() {
        when(bookingService.getAppointment(ARJUN, 99)).thenReturn(APPOINTMENT);

        ActionResult prepared = tools.prepareCancellation(99, as(ARJUN, 1));
        assertEquals(Status.NEEDS_CONFIRMATION, prepared.status());
        verify(bookingService, never()).cancel(anyLong(), anyLong());

        assertEquals(Status.NOT_CONFIRMED_YET, tools.confirmPendingAction(as(ARJUN, 1)).status());
        assertEquals(Status.CANCELLED, tools.confirmPendingAction(as(ARJUN, 2)).status());
        verify(bookingService, times(1)).cancel(ARJUN, 99);
    }

    @Test
    void prepareCancellation_ofSomeoneElsesAppointment_isReportedAsNotFound() {
        // BookingService.getAppointment is owner-only: for another member's appointment it throws 403.
        when(bookingService.getAppointment(LENA, 99)).thenThrow(new ForbiddenException("You can only view your own appointments"));

        ActionResult result = tools.prepareCancellation(99, as(LENA, 1));

        assertEquals(Status.NOT_FOUND, result.status());
        assertTrue(pendingActions.find(LENA).isEmpty());
        verify(bookingService, never()).cancel(anyLong(), anyLong());
    }

    // ---- read tools and identity ----

    @Test
    void checkAvailability_passesFiltersToTheSlotService_andReturnsSlotViews() {
        when(slotService.browseAvailable(eq(2L), eq(SERVICE_ID), eq("2026-11-18"), eq(0), anyInt()))
                .thenReturn(new PageResult<>(List.of(OTHER_SLOT), 0, 12, 1));

        AvailabilityResult result = tools.checkAvailability("2026-11-18", SERVICE_ID, 2L);

        assertEquals(Status.OK, result.status());
        assertEquals(1, result.totalOpenSlotsMatching());
        assertEquals("Wednesday", result.slots().get(0).dayOfWeek());
        assertEquals(43, result.slots().get(0).slotId());
    }

    @Test
    void checkAvailability_withABadDate_returnsTheValidationMessageInsteadOfThrowing() {
        when(slotService.browseAvailable(any(), any(), eq("tomorrow"), anyInt(), anyInt()))
                .thenThrow(new BadRequestException("date must be in yyyy-MM-dd format"));

        AvailabilityResult result = tools.checkAvailability("tomorrow", null, null);

        assertEquals(Status.INVALID_INPUT, result.status());
        assertEquals("date must be in yyyy-MM-dd format", result.message());
    }

    @Test
    void listMyAppointments_usesTheMemberIdFromTheToolContext() {
        when(bookingService.listAppointments(ARJUN)).thenReturn(new com.fitbook.dto.MyAppointmentsDTO(List.of(APPOINTMENT), List.of()));

        assertEquals(1, tools.listMyAppointments(as(ARJUN, 1)).upcoming().size());
        verify(bookingService).listAppointments(ARJUN);
    }

    @Test
    void aToolCalledWithoutAnIdentity_failsInsteadOfGuessing() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> tools.prepareBooking(SLOT_ID, new ToolContext(Map.of())));
        assertNotNull(error.getMessage());
        verifyNoInteractions(bookingService);
    }
}
