package com.fitbook.ai.assistant;

import com.fitbook.ai.AiProperties;
import com.fitbook.ai.assistant.ToolResults.ActionResult;
import com.fitbook.ai.assistant.ToolResults.AppointmentView;
import com.fitbook.ai.assistant.ToolResults.AvailabilityResult;
import com.fitbook.ai.assistant.ToolResults.MyAppointmentsResult;
import com.fitbook.ai.assistant.ToolResults.SlotView;
import com.fitbook.ai.assistant.ToolResults.Status;
import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.MyAppointmentsDTO;
import com.fitbook.dto.PageResult;
import com.fitbook.dto.ServiceDTO;
import com.fitbook.exception.ApiException;
import com.fitbook.exception.ConflictException;
import com.fitbook.service.BookingService;
import com.fitbook.service.CatalogService;
import com.fitbook.service.SlotService;
import com.fitbook.util.SlotTime;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The tools Agent 1 can call. Each one is a thin wrapper over an existing service-layer method;
 * no tool touches a repository or runs SQL. Three rules hold for every tool:
 *
 * <ul>
 *   <li><b>Identity comes from the server.</b> The member's id is read from the ToolContext, which
 *       the chat service fills from the HTTP session. It is never a tool argument, so the model
 *       cannot act for anyone else.</li>
 *   <li><b>Writes go through BookingService.</b> Booking and cancelling use the same transactional
 *       methods as the web pages, so the version check, the unique index, the retry policy and the
 *       owner check all still apply.</li>
 *   <li><b>Writes need two turns.</b> prepare_* only records a PendingAction. confirm_pending_action
 *       performs it, and refuses if it was prepared in the same turn. A PendingAction waits for
 *       exactly one reply: AssistantService drops it if the next turn does not confirm it.</li>
 * </ul>
 */
@Component
public class BookingTools {

    /** ToolContext keys set by AssistantService for every call. */
    public static final String USER_ID = "userId";
    public static final String TURN = "turn";

    static final int MAX_SLOTS = 12;
    static final int MAX_ALTERNATIVES = 3;

    private static final Logger log = LoggerFactory.getLogger(BookingTools.class);

    private final SlotService slotService;
    private final BookingService bookingService;
    private final CatalogService catalogService;
    private final PendingActionStore pendingActions;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public BookingTools(SlotService slotService, BookingService bookingService, CatalogService catalogService,
                        PendingActionStore pendingActions, AiProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.slotService = slotService;
        this.bookingService = bookingService;
        this.catalogService = catalogService;
        this.pendingActions = pendingActions;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- read tools

    @Tool(name = "check_availability", description = """
            Finds open, bookable time slots, earliest first. Every filter is optional; with no filters it returns \
            the soonest open slots across all services and trainers. Always call this before proposing a slot.""")
    public AvailabilityResult checkAvailability(
            @ToolParam(required = false, description = "Only slots on this date, format yyyy-MM-dd") String date,
            @ToolParam(required = false, description = "Only this service, by its id") Long serviceId,
            @ToolParam(required = false, description = "Only this trainer, by their id") Long trainerId) {
        try {
            String day = date == null || date.isBlank() ? null : date.trim();
            PageResult<AvailabilitySlotDTO> page = slotService.browseAvailable(trainerId, serviceId, day, 0, MAX_SLOTS);
            List<SlotView> slots = page.items().stream().map(BookingTools::toView).toList();
            count("check_availability", Status.OK);
            log.info("Agent tool check_availability date={} serviceId={} trainerId={} found={}", day, serviceId, trainerId, page.totalCount());
            String message = slots.isEmpty() ? "No open slots match these filters." : null;
            return new AvailabilityResult(Status.OK, message, page.totalCount(), slots);
        } catch (ApiException e) {
            count("check_availability", Status.INVALID_INPUT);
            return new AvailabilityResult(Status.INVALID_INPUT, e.getMessage(), 0, List.of());
        }
    }

    @Tool(name = "list_my_appointments", description = """
            Lists the logged-in member's own appointments: upcoming ones (status BOOKED, these can be cancelled) \
            and history (COMPLETED or CANCELLED).""")
    public MyAppointmentsResult listMyAppointments(ToolContext context) {
        long userId = userId(context);
        MyAppointmentsDTO mine = bookingService.listAppointments(userId);
        count("list_my_appointments", Status.OK);
        log.info("Agent tool list_my_appointments userId={} upcoming={}", userId, mine.upcoming().size());
        return new MyAppointmentsResult(Status.OK, null,
                mine.upcoming().stream().map(BookingTools::toView).toList(),
                mine.history().stream().map(BookingTools::toView).toList());
    }

    // ---------------------------------------------------------------- write tools (two-step)

    @Tool(name = "prepare_booking", description = """
            Step 1 of booking. Checks the slot and prepares a booking for the logged-in member WITHOUT booking it. \
            Returns a summary to show the member. Nothing is booked until confirm_pending_action runs in a later turn.""")
    public ActionResult prepareBooking(
            @ToolParam(description = "The slotId of an open slot returned by check_availability") long slotId,
            ToolContext context) {
        long userId = userId(context);
        AvailabilitySlotDTO slot;
        try {
            slot = slotService.getSlot(slotId);
        } catch (ApiException e) {
            return done("prepare_booking", userId, ActionResult.of(Status.NOT_FOUND,
                    "There is no slot with id " + slotId + ". Call check_availability to get real slot ids."));
        }
        boolean bookable = "OPEN".equals(slot.status()) && slot.startTime().compareTo(SlotTime.now(clock)) > 0;
        if (!bookable) {
            return done("prepare_booking", userId, new ActionResult(Status.UNAVAILABLE,
                    "That slot is no longer available. Tell the member and offer these alternatives for the same service.",
                    null, null, alternatives(slot.serviceId())));
        }
        String summary = "Book %s with %s on %s %s to %s%s (slot #%d)".formatted(
                slot.serviceName(), slot.providerName(), dayOfWeek(slot.startTime()), slot.startTime(),
                slot.endTime().substring(11), price(slot.serviceId()), slot.id());
        pendingActions.put(new PendingAction(PendingAction.Kind.BOOK, userId, slot.id(), slot.serviceId(), summary,
                firstPreparedIn(PendingAction.Kind.BOOK, userId, slot.id(), context), turn(context),
                clock.instant().plus(properties.confirmationTtl())));
        return done("prepare_booking", userId, new ActionResult(Status.NEEDS_CONFIRMATION,
                "NOT booked yet. Show this summary to the member and ask them to confirm. "
                        + "Call confirm_pending_action only after they say yes in their next message.",
                summary, null, null));
    }

    @Tool(name = "prepare_cancellation", description = """
            Step 1 of cancelling. Checks that the appointment belongs to the logged-in member and is still BOOKED, \
            and prepares the cancellation WITHOUT cancelling. Nothing changes until confirm_pending_action runs in a later turn.""")
    public ActionResult prepareCancellation(
            @ToolParam(description = "The appointmentId of one of the member's upcoming appointments, from list_my_appointments") long appointmentId,
            ToolContext context) {
        long userId = userId(context);
        AppointmentDTO appointment;
        try {
            // Owner-only read: someone else's appointment comes back as 403 and is reported as "not yours".
            appointment = bookingService.getAppointment(userId, appointmentId);
        } catch (ApiException e) {
            return done("prepare_cancellation", userId, ActionResult.of(Status.NOT_FOUND,
                    "The member has no appointment with id " + appointmentId + ". Call list_my_appointments to get their appointments."));
        }
        if (!"BOOKED".equals(appointment.status())) {
            return done("prepare_cancellation", userId, new ActionResult(Status.UNAVAILABLE,
                    "That appointment is " + appointment.status() + " and cannot be cancelled.", null, toView(appointment), null));
        }
        String summary = "Cancel appointment #%d: %s with %s on %s %s".formatted(appointment.id(),
                appointment.serviceName(), appointment.providerName(), dayOfWeek(appointment.startTime()), appointment.startTime());
        pendingActions.put(new PendingAction(PendingAction.Kind.CANCEL, userId, appointment.id(), 0, summary,
                firstPreparedIn(PendingAction.Kind.CANCEL, userId, appointment.id(), context), turn(context),
                clock.instant().plus(properties.confirmationTtl())));
        return done("prepare_cancellation", userId, new ActionResult(Status.NEEDS_CONFIRMATION,
                "NOT cancelled yet. Show this summary to the member and ask them to confirm. "
                        + "Call confirm_pending_action only after they say yes in their next message.",
                summary, toView(appointment), null));
    }

    @Tool(name = "confirm_pending_action", description = """
            Step 2. Performs the booking or cancellation that was prepared earlier, but only call it after the \
            member has clearly said yes to the summary in a message of their own. Takes no arguments.""")
    public ActionResult confirmPendingAction(ToolContext context) {
        long userId = userId(context);
        Optional<PendingAction> waiting = pendingActions.find(userId);
        if (waiting.isEmpty()) {
            return done("confirm_pending_action", userId, ActionResult.of(Status.NOTHING_PENDING,
                    "Nothing is waiting for confirmation (it may have expired). Prepare the booking or cancellation again."));
        }
        PendingAction action = waiting.get();
        // The guard that makes confirm-before-write real: the member must have sent a message since the summary.
        if (action.preparedInTurn() >= turn(context)) {
            return done("confirm_pending_action", userId, new ActionResult(Status.NOT_CONFIRMED_YET,
                    "Refused: the member has not confirmed yet, this was prepared in the current turn. "
                            + "Show the summary, ask them to confirm, and wait for their reply.",
                    action.summary(), null, null));
        }
        if (!pendingActions.take(action)) {
            return done("confirm_pending_action", userId, ActionResult.of(Status.NOTHING_PENDING,
                    "Nothing is waiting for confirmation any more."));
        }
        return done("confirm_pending_action", userId,
                action.kind() == PendingAction.Kind.BOOK ? book(action) : cancel(action));
    }

    // ---------------------------------------------------------------- the actual writes

    /** Same call the web page makes: BookingService.book runs the transaction with the double-booking guard. */
    private ActionResult book(PendingAction action) {
        try {
            AppointmentDTO appointment = bookingService.book(action.userId(),
                    new BookingRequest(action.targetId(), action.serviceId()));
            return new ActionResult(Status.BOOKED, "Booked. Tell the member it is confirmed.", null, toView(appointment), null);
        } catch (ConflictException e) {
            return new ActionResult(Status.SLOT_TAKEN,
                    "Not booked: " + e.getMessage() + ". Apologise and offer these alternatives for the same service.",
                    null, null, alternatives(action.serviceId()));
        } catch (ApiException e) {
            return ActionResult.of(Status.FAILED, "Not booked: " + e.getMessage());
        }
    }

    private ActionResult cancel(PendingAction action) {
        try {
            bookingService.cancel(action.userId(), action.targetId());
            return ActionResult.of(Status.CANCELLED, "Cancelled. The slot is open again. " + action.summary());
        } catch (ApiException e) {
            return ActionResult.of(Status.FAILED, "Not cancelled: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Preparing the very same action again does not restart the wait: it keeps the turn in which
     * the member was first shown the summary. (Models often re-run prepare right before confirm.)
     * Preparing anything different counts from the current turn, so it needs a fresh confirmation.
     */
    private long firstPreparedIn(PendingAction.Kind kind, long userId, long targetId, ToolContext context) {
        return pendingActions.find(userId)
                .filter(waiting -> waiting.kind() == kind && waiting.targetId() == targetId)
                .map(PendingAction::preparedInTurn)
                .orElse(turn(context));
    }

    private List<SlotView> alternatives(long serviceId) {
        try {
            return slotService.browseAvailable(null, serviceId, null, 0, MAX_ALTERNATIVES).items().stream()
                    .map(BookingTools::toView).toList();
        } catch (ApiException e) {
            return List.of();
        }
    }

    private String price(long serviceId) {
        return catalogService.listServices().stream()
                .filter(s -> s.id() == serviceId)
                .map(ServiceDTO::price)
                .findFirst()
                .map(p -> ", $%.2f".formatted(p))
                .orElse("");
    }

    private ActionResult done(String tool, long userId, ActionResult result) {
        count(tool, result.status());
        log.info("Agent tool {} userId={} status={}", tool, userId, result.status());
        return result;
    }

    private void count(String tool, Status status) {
        meterRegistry.counter("fitbook.agent.tool.calls", "tool", tool, "result", status.name()).increment();
    }

    private static long userId(ToolContext context) {
        return number(context, USER_ID);
    }

    private static long turn(ToolContext context) {
        return number(context, TURN);
    }

    private static long number(ToolContext context, String key) {
        Object value = context == null ? null : context.getContext().get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Tool called without '" + key + "' in the tool context");
        }
        return number.longValue();
    }

    private static SlotView toView(AvailabilitySlotDTO slot) {
        return new SlotView(slot.id(), slot.serviceId(), slot.serviceName(), slot.providerName(),
                dayOfWeek(slot.startTime()), slot.startTime(), slot.endTime());
    }

    private static AppointmentView toView(AppointmentDTO appointment) {
        return new AppointmentView(appointment.id(), appointment.serviceName(), appointment.providerName(),
                dayOfWeek(appointment.startTime()), appointment.startTime(), appointment.endTime(), appointment.status());
    }

    private static String dayOfWeek(String dbTime) {
        try {
            return LocalDateTime.parse(dbTime, SlotTime.DB_FORMAT).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
        } catch (DateTimeParseException e) {
            return "";
        }
    }
}
