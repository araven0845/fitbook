package com.fitbook.controller;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.PageResult;
import com.fitbook.service.SlotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint returning DTOs as JSON. Together with a client-side router
 * this style plays the Front Controller role (a single entry point for API
 * traffic) - see the Milestone 1 report for the Page vs Front Controller
 * discussion.
 */
@RestController
public class SlotApiController {

    private final SlotService slotService;

    public SlotApiController(SlotService slotService) {
        this.slotService = slotService;
    }

    @GetMapping("/slots")
    public PageResult<AvailabilitySlotDTO> availableSlots(
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return slotService.browseAvailable(providerId, serviceId, date, page, size);
    }
}
