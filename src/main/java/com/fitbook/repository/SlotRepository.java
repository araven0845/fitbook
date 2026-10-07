package com.fitbook.repository;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.model.SlotRecord;

import java.util.List;
import java.util.Optional;

public interface SlotRepository {

    /**
     * Filtered, paginated read of OPEN availability slots.
     *
     * @param providerId optional provider filter
     * @param serviceId  optional service filter
     * @param date       optional date filter, format yyyy-MM-dd
     * @param now        current local time (yyyy-MM-dd HH:mm), slots that already started are excluded
     * @param limit      page size
     * @param offset     page offset
     */
    List<AvailabilitySlotDTO> findAvailable(Long providerId, Long serviceId, String date, String now, int limit, int offset);

    long countAvailable(Long providerId, Long serviceId, String date, String now);

    Optional<SlotRecord> findById(long id);

    Optional<AvailabilitySlotDTO> findDetailById(long id);

    /**
     * Optimistic booking update: flips OPEN -> BOOKED and bumps the version, but only if the
     * slot still has the version the caller read. Returns 0 rows when someone else got there first.
     */
    int markBooked(long slotId, long expectedVersion);

    /** BOOKED -> OPEN again (appointment cancelled), bumping the version. */
    int reopen(long slotId);

    /** A provider's live (non-cancelled) slots that have not ended yet, soonest first. */
    List<AvailabilitySlotDTO> findByProvider(long providerId, String now);

    /** True if the provider already has a live slot overlapping [start, end). */
    boolean existsOverlap(long providerId, String start, String end);

    /** Inserts an OPEN slot and returns its id. */
    long insert(long providerId, long serviceId, String start, String end);

    /** OPEN -> CANCELLED (provider removed the slot). Returns 0 rows if the slot is no longer OPEN. */
    int cancelOpen(long slotId);
}
