package com.fitbook.controller;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.SessionUser;
import com.fitbook.exception.ConflictException;
import com.fitbook.model.Role;
import com.fitbook.security.RequiresRole;
import com.fitbook.service.BookingService;
import com.fitbook.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Customer-only pages: book a slot, see the confirmation, list and cancel my appointments. */
@Controller
@RequiresRole(Role.CUSTOMER)
public class BookingController {

    private final BookingService bookingService;
    private final SlotService slotService;

    public BookingController(BookingService bookingService, SlotService slotService) {
        this.bookingService = bookingService;
        this.slotService = slotService;
    }

    @GetMapping("/book")
    public String bookForm(@RequestParam long slotId, Model model) {
        AvailabilitySlotDTO slot = slotService.getSlot(slotId);
        if (!"OPEN".equals(slot.status())) {
            throw new ConflictException("This slot is no longer available");
        }
        model.addAttribute("slot", slot);
        return "book";
    }

    @PostMapping("/book")
    public String book(SessionUser user, @Valid @ModelAttribute BookingRequest bookingRequest) {
        AppointmentDTO appointment = bookingService.book(user.id(), bookingRequest);
        return "redirect:/confirmation/" + appointment.id();
    }

    @GetMapping("/confirmation/{id}")
    public String confirmation(SessionUser user, @PathVariable long id, Model model) {
        model.addAttribute("appointment", bookingService.getAppointment(user.id(), id));
        return "confirmation";
    }

    @GetMapping("/my-appointments")
    public String myAppointments(SessionUser user, Model model) {
        model.addAttribute("appointments", bookingService.listAppointments(user.id()));
        return "my-appointments";
    }

    @PostMapping("/appointments/{id}/cancel")
    public String cancel(SessionUser user, @PathVariable long id, RedirectAttributes redirect) {
        bookingService.cancel(user.id(), id);
        redirect.addFlashAttribute("message", "Appointment cancelled. The slot is open again.");
        return "redirect:/my-appointments";
    }
}
