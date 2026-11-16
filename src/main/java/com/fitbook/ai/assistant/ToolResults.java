package com.fitbook.ai.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What the tools hand back to the model, serialized to JSON by Spring AI. These are small
 * purpose-built views: the model sees ids, names and times, and nothing else from the database.
 */
public final class ToolResults {

    private ToolResults() {}

    public enum Status {
        OK,
        /** Prepared and waiting for the member's yes. Nothing has been written. */
        NEEDS_CONFIRMATION,
        /** confirm was called in the same turn as prepare: refused, nothing written. */
        NOT_CONFIRMED_YET,
        BOOKED,
        CANCELLED,
        /** The slot is not open (booked, removed or already started). */
        UNAVAILABLE,
        /** Someone else booked the slot between prepare and confirm. */
        SLOT_TAKEN,
        NOT_FOUND,
        NOTHING_PENDING,
        INVALID_INPUT,
        FAILED
    }

    public record SlotView(long slotId, long serviceId, String service, String trainer,
                           String dayOfWeek, String start, String end) {}

    public record AppointmentView(long appointmentId, String service, String trainer,
                                  String dayOfWeek, String start, String end, String status) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record AvailabilityResult(Status status, String message, long totalOpenSlotsMatching, List<SlotView> slots) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MyAppointmentsResult(Status status, String message,
                                       List<AppointmentView> upcoming, List<AppointmentView> history) {}

    /** Result of every prepare / confirm / discard call. `message` tells the model what to do next. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActionResult(Status status, String message, String summary,
                               AppointmentView appointment, List<SlotView> alternatives) {

        static ActionResult of(Status status, String message) {
            return new ActionResult(status, message, null, null, null);
        }
    }
}
