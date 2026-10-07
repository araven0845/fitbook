package com.fitbook.controller;

import com.fitbook.dto.CreateSlotRequest;
import com.fitbook.dto.SessionUser;
import com.fitbook.model.Role;
import com.fitbook.security.RequiresRole;
import com.fitbook.service.CatalogService;
import com.fitbook.service.ProviderService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Provider-only pages: manage my availability slots and see who booked with me. */
@Controller
@RequiresRole(Role.PROVIDER)
public class ProviderController {

    private final ProviderService providerService;
    private final CatalogService catalogService;

    public ProviderController(ProviderService providerService, CatalogService catalogService) {
        this.providerService = providerService;
        this.catalogService = catalogService;
    }

    @GetMapping("/provider")
    public String dashboard(SessionUser user, Model model) {
        model.addAttribute("slots", providerService.listSlots(user.id()));
        model.addAttribute("services", catalogService.listServices());
        return "provider-dashboard";
    }

    @PostMapping("/provider/slots")
    public String createSlot(SessionUser user, @Valid @ModelAttribute CreateSlotRequest createSlotRequest,
                             RedirectAttributes redirect) {
        providerService.createSlot(user.id(), createSlotRequest);
        redirect.addFlashAttribute("message", "Slot created.");
        return "redirect:/provider";
    }

    @PostMapping("/provider/slots/{id}/remove")
    public String removeSlot(SessionUser user, @PathVariable long id, RedirectAttributes redirect) {
        providerService.removeSlot(user.id(), id);
        redirect.addFlashAttribute("message", "Slot removed.");
        return "redirect:/provider";
    }

    @GetMapping("/provider/appointments")
    public String appointments(SessionUser user, Model model) {
        model.addAttribute("appointments", providerService.listAppointments(user.id()));
        return "provider-appointments";
    }
}
