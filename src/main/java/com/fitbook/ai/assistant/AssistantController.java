package com.fitbook.ai.assistant;

import com.fitbook.ai.AiAvailability;
import com.fitbook.dto.SessionUser;
import com.fitbook.model.Role;
import com.fitbook.security.RequiresRole;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * The chat endpoint for Agent 1 plus the page that uses it. Customer-only: the same @RequiresRole
 * check that protects the booking pages runs before any message reaches the model, and the
 * SessionUser passed down is the identity every tool call acts as.
 */
@Controller
@RequiresRole(Role.CUSTOMER)
public class AssistantController {

    private final AssistantService assistantService;
    private final AiAvailability availability;

    public AssistantController(AssistantService assistantService, AiAvailability availability) {
        this.assistantService = assistantService;
        this.availability = availability;
    }

    @GetMapping("/assistant")
    public String page(Model model) {
        model.addAttribute("aiConfigured", availability.isConfigured());
        return "assistant";
    }

    @PostMapping("/assistant/chat")
    @ResponseBody
    public ChatReply chat(SessionUser user, @Valid @RequestBody ChatRequest request) {
        return assistantService.chat(user, request.message().strip());
    }

    /** The saved conversation, so the page can show it again after a reload, a new login or a restart. */
    @GetMapping("/assistant/history")
    @ResponseBody
    public ChatHistory history(SessionUser user) {
        return new ChatHistory(assistantService.history(user), assistantService.pendingSummary(user));
    }

    @DeleteMapping("/assistant/history")
    public ResponseEntity<Void> clearHistory(SessionUser user) {
        assistantService.clear(user);
        return ResponseEntity.noContent().build();
    }

    public record ChatHistory(List<ChatMessageView> messages, String pendingAction) {}
}
