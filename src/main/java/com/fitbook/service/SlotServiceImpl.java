package com.fitbook.service;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.PageResult;
import com.fitbook.repository.SlotRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SlotServiceImpl implements SlotService {

    private final SlotRepository slotRepository;

    public SlotServiceImpl(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Override
    public PageResult<AvailabilitySlotDTO> browseAvailable(Long providerId, Long serviceId, String date, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = safePage * safeSize;

        List<AvailabilitySlotDTO> items = slotRepository.findAvailable(providerId, serviceId, date, safeSize, offset);
        long total = slotRepository.countAvailable(providerId, serviceId, date);

        return new PageResult<>(items, safePage, safeSize, total);
    }
}
