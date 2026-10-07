package com.fitbook.repository;

import com.fitbook.dto.AvailabilitySlotDTO;

import java.util.List;

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
}
