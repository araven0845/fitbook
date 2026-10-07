package com.fitbook.service;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.PageResult;
import com.fitbook.exception.BadRequestException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.repository.SlotRepository;
import com.fitbook.util.SlotTime;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class SlotServiceImpl implements SlotService {

    private final SlotRepository slotRepository;
    private final Clock clock;

    public SlotServiceImpl(SlotRepository slotRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.clock = clock;
    }

    @Override
    public PageResult<AvailabilitySlotDTO> browseAvailable(Long providerId, Long serviceId, String date, int page, int size) {
        if (date != null && !date.isBlank()) {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("date must be in yyyy-MM-dd format");
            }
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = safePage * safeSize;

        String now = SlotTime.now(clock);
        List<AvailabilitySlotDTO> items = slotRepository.findAvailable(providerId, serviceId, date, now, safeSize, offset);
        long total = slotRepository.countAvailable(providerId, serviceId, date, now);

        return new PageResult<>(items, safePage, safeSize, total);
    }

    @Override
    public AvailabilitySlotDTO getSlot(long slotId) {
        return slotRepository.findDetailById(slotId).orElseThrow(() -> new NotFoundException("Slot not found"));
    }
}
