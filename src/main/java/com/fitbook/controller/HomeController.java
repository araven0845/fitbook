package com.fitbook.controller;

import com.fitbook.service.CatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page Controller: server-renders a Thymeleaf view, one controller per page,
 * as opposed to a Front Controller (single DispatcherServlet-style entry
 * point serving JSON to a client-side router - see SlotApiController and the
 * Milestone 1 report for the comparison).
 */
@Controller
public class HomeController {

    private final CatalogService catalogService;

    public HomeController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("services", catalogService.listServices());
        model.addAttribute("providers", catalogService.listProviders());
        return "home";
    }

    @GetMapping("/book")
    public String bookStub() {
        // Wireframe stub only - the real booking flow (POST /appointments,
        // concurrency-safe) is not implemented yet.
        return "book";
    }

    @GetMapping("/confirmation")
    public String confirmationStub() {
        return "confirmation";
    }
}
