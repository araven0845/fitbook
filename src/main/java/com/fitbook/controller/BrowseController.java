package com.fitbook.controller;

import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.PageResult;
import com.fitbook.service.CatalogService;
import com.fitbook.service.SlotService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Page Controller for the "view available slots" screen (filter by provider, service, date; paginated). */
@Controller
public class BrowseController {

    private final SlotService slotService;
    private final CatalogService catalogService;

    public BrowseController(SlotService slotService, CatalogService catalogService) {
        this.slotService = slotService;
        this.catalogService = catalogService;
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(required = false) Long providerId,
                         @RequestParam(required = false) Long serviceId,
                         @RequestParam(required = false) String date,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "10") int size,
                         Model model) {
        PageResult<AvailabilitySlotDTO> result = slotService.browseAvailable(providerId, serviceId, date, page, size);
        long totalPages = Math.max(1, (result.totalCount() + result.size() - 1) / result.size());

        model.addAttribute("result", result);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("providers", catalogService.listProviders());
        model.addAttribute("services", catalogService.listServices());
        model.addAttribute("providerId", providerId);
        model.addAttribute("serviceId", serviceId);
        model.addAttribute("date", date);
        return "browse";
    }
}
