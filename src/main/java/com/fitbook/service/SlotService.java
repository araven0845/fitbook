package com.fitbook.service;

import com.fitbook.dto.PageResult;
import com.fitbook.dto.AvailabilitySlotDTO;

public interface SlotService {
    PageResult<AvailabilitySlotDTO> browseAvailable(Long providerId, Long serviceId, String date, int page, int size);
}
