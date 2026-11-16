package com.fitbook.ai.assistant;

import com.fitbook.ai.AiAvailability;
import com.fitbook.ai.AiConfig;
import com.fitbook.ai.knowledge.KnowledgeBase;
import com.fitbook.dto.SessionUser;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.ServiceUnavailableException;
import com.fitbook.model.Role;
import com.fitbook.service.CatalogService;
import com.google.genai.errors.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 1, the conversational agent. One call here is one chat turn: the member's message goes to
 * Gemini through the ChatClient together with the tools, the remembered conversation and the
 * retrieved FAQ sections. Gemini may call several tools in a row (Spring AI runs each call and
 * feeds the result back) before it writes the reply.
 */
@Service
public class AssistantService {

    static final String SYSTEM_PROMPT = """
            You are FitBook Assistant, the booking assistant of FitBook, a fitness studio.
            You are talking with %s, a logged-in member. Right now it is %s.

            You can: find open slots, list this member's appointments, book a slot, cancel an appointment, \
            and answer questions about studio policies and services.

            Services and trainers (use these ids as tool arguments):
            %s

            Rules:
            1. Facts come from tools. Never invent slots, appointments or times. Call check_availability \
            before you propose a slot, and list_my_appointments before you talk about the member's appointments.
            2. Booking and cancelling take two steps. First call prepare_booking or prepare_cancellation, show the \
            summary it returns, and ask the member to confirm. Only after the member clearly says yes in their next \
            message, call confirm_pending_action. If they say no or talk about something else, do not call it: the \
            prepared request is dropped automatically, and you can tell them nothing was changed. \
            Never tell the member something is booked or cancelled unless a tool returned BOOKED or CANCELLED.
            3. For a request like "find the earliest slot this week and book it": check availability, pick the slot, \
            prepare the booking, and ask for confirmation, all in one reply.
            4. If a slot is unavailable or was taken, say so and offer the alternatives the tool returned.
            5. If the request is unclear (which service, which day, which appointment), ask one short question.
            6. When you list slots or appointments, include the id, like "(slot #7)" or "(appointment #3)", so the \
            member can refer to it later.
            7. Answer questions about policies, opening hours, prices, what to bring and studio rules only from the \
            "Studio knowledge" sections attached to the message. If they do not cover it, say you do not know and \
            suggest asking the front desk.
            8. You act only for this member. You cannot see or change anyone else's bookings. Politely decline \
            anything that is not about FitBook.
            9. Reply in short plain text without markdown. Write times in a friendly form such as Tue Nov 17, 9:00 AM.
            """;

    static final String NOT_CONFIGURED =
            "The AI assistant is not configured on this server (no Gemini API key). You can still book from the Browse page.";
    static final String UNAVAILABLE =
            "The assistant is not available right now. Please try again in a moment, or book from the Browse page.";
    static final String RATE_LIMITED =
            "The assistant is getting more requests than its free AI plan allows per minute. Please wait a minute and try again.";
    static final String EMPTY_REPLY = "Sorry, I could not come up with an answer. Could you rephrase that?";

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm", Locale.US);

    private final ChatClient chatClient;
    private final BookingTools bookingTools;
    private final PendingActionStore pendingActions;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository memoryRepository;
    private final CatalogService catalogService;
    private final AiAvailability availability;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** Every chat request gets the next number; the tools use it to tell "this turn" from "an earlier turn". */
    private final AtomicLong turns = new AtomicLong();
    /** One turn at a time per member, so two tabs cannot interleave their memory updates. */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public AssistantService(@Qualifier(AiConfig.ASSISTANT_CLIENT) ChatClient chatClient, BookingTools bookingTools,
                            PendingActionStore pendingActions, ChatMemory chatMemory, ChatMemoryRepository memoryRepository,
                            CatalogService catalogService, AiAvailability availability, MeterRegistry meterRegistry, Clock clock) {
        this.chatClient = chatClient;
        this.bookingTools = bookingTools;
        this.pendingActions = pendingActions;
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
        this.catalogService = catalogService;
        this.availability = availability;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public ChatReply chat(SessionUser user, String message) {
        requireCustomer(user);
        if (!availability.isConfigured()) {
            throw new ServiceUnavailableException(NOT_CONFIGURED);
        }
        synchronized (locks.computeIfAbsent(user.id(), id -> new Object())) {
            long turn = turns.incrementAndGet();
            String conversationId = conversationId(user.id());
            try {
                ChatResponse response = chatClient.prompt()
                        .system(SYSTEM_PROMPT.formatted(user.fullName(), NOW_FORMAT.format(LocalDateTime.now(clock)), catalog()))
                        .user(message)
                        .tools(bookingTools)
                        // The tools read who is asking from here, never from the model.
                        .toolContext(Map.of(BookingTools.USER_ID, user.id(), BookingTools.TURN, turn))
                        .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .chatResponse();

                // A prepared booking or cancellation waits for exactly one reply. If this turn did not
                // confirm it (and did not just prepare it), it is gone, whatever the model said.
                pendingActions.dropIfLeftUnconfirmed(user.id(), turn);

                String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
                meterRegistry.counter("fitbook.agent.chat", "result", "ok").increment();
                log.info("Assistant turn={} userId={} answered", turn, user.id());
                return new ChatReply(
                        text == null || text.isBlank() ? EMPTY_REPLY : text.strip(),
                        pendingActions.find(user.id()).map(PendingAction::summary).orElse(null),
                        retrievedSections(response));
            } catch (RuntimeException e) {
                // Rate limit, timeout, network, bad key... The member gets a 503 with a plain message, never a stack trace.
                Throwable cause = rootCause(e);
                boolean rateLimited = cause instanceof ApiException apiError && apiError.code() == 429;
                meterRegistry.counter("fitbook.agent.chat", "result", rateLimited ? "rate_limited" : "failed").increment();
                log.warn("Assistant turn={} userId={} failed: {}", turn, user.id(), describe(cause));
                forgetUnansweredMessage(conversationId);
                throw new ServiceUnavailableException(rateLimited ? RATE_LIMITED : UNAVAILABLE);
            }
        }
    }

    /** The remembered conversation, oldest first. */
    public List<ChatMessageView> history(SessionUser user) {
        requireCustomer(user);
        List<ChatMessageView> views = new ArrayList<>();
        for (Message message : chatMemory.get(conversationId(user.id()))) {
            if (message.getMessageType() == MessageType.USER) {
                views.add(new ChatMessageView("user", message.getText()));
            } else if (message.getMessageType() == MessageType.ASSISTANT) {
                views.add(new ChatMessageView("assistant", message.getText()));
            }
        }
        return views;
    }

    public String pendingSummary(SessionUser user) {
        requireCustomer(user);
        return pendingActions.find(user.id()).map(PendingAction::summary).orElse(null);
    }

    /** Forgets the conversation and drops anything waiting for confirmation. */
    public void clear(SessionUser user) {
        requireCustomer(user);
        chatMemory.clear(conversationId(user.id()));
        pendingActions.discard(user.id());
    }

    /**
     * The studio's short, fixed list of services and trainers, read through the service layer and
     * placed in the system prompt. It saves the model a tool round trip on almost every request,
     * which matters on the free tier's requests-per-minute limit.
     */
    private String catalog() {
        StringBuilder text = new StringBuilder();
        catalogService.listServices().forEach(s -> text.append("- service id %d: %s, %d minutes, $%.2f%n"
                .formatted(s.id(), s.name(), s.durationMinutes(), s.price())));
        catalogService.listProviders().forEach(p -> text.append("- trainer id %d: %s (%s)%n"
                .formatted(p.id(), p.fullName(), p.specialty())));
        return text.toString().strip();
    }

    static String conversationId(long userId) {
        return "user-" + userId;
    }

    /** The controller already restricts the endpoint to customers; this keeps the rule next to the agent too. */
    private static void requireCustomer(SessionUser user) {
        if (user == null || user.role() != Role.CUSTOMER) {
            throw new ForbiddenException("The booking assistant is only available to customer accounts");
        }
    }

    /** If the model call failed, the memory advisor has already stored the question; take it back out. */
    private void forgetUnansweredMessage(String conversationId) {
        try {
            List<Message> messages = memoryRepository.findByConversationId(conversationId);
            if (!messages.isEmpty() && messages.get(messages.size() - 1).getMessageType() == MessageType.USER) {
                memoryRepository.saveAll(conversationId, messages.subList(0, messages.size() - 1));
            }
        } catch (RuntimeException e) {
            log.debug("Could not tidy conversation memory after a failed turn: {}", e.getMessage());
        }
    }

    private static List<String> retrievedSections(ChatResponse response) {
        if (response == null || !(response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS) instanceof List<?> documents)) {
            return List.of();
        }
        return documents.stream()
                .filter(Document.class::isInstance)
                .map(d -> String.valueOf(((Document) d).getMetadata().get(KnowledgeBase.SECTION)))
                .toList();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** One log line's worth of the error: its type and the first part of its message. */
    private static String describe(Throwable cause) {
        String message = String.valueOf(cause.getMessage()).replaceAll("\\s+", " ");
        return cause.getClass().getSimpleName() + ": " + (message.length() > 450 ? message.substring(0, 450) + "..." : message);
    }
}
