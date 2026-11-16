package com.fitbook.ai;

import com.fitbook.FitbookIntegrationTest;
import com.fitbook.ai.assistant.PendingActionStore;
import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.SessionUser;
import com.fitbook.model.Role;
import com.fitbook.model.SlotStatus;
import com.fitbook.model.UserRecord;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.security.SessionKeys;
import com.fitbook.service.BookingService;
import com.fitbook.testsupport.HashingEmbeddingModel;
import com.fitbook.testsupport.ScriptedChatModel;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 1 end to end through the chat endpoint, with Gemini replaced by a scripted model. The
 * script decides which tools "the model" calls; everything else is real: the controller and its
 * role check, the ChatClient, both advisors (memory and RAG), Spring AI's tool-calling loop, the
 * tools, the booking service and SQLite.
 */
@FitbookIntegrationTest
class AssistantChatTest {

    private static final long PROVIDER_ID = 1;
    private static final long SERVICE_ID = 1;
    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ScriptedChatModel model;
    @Autowired private HashingEmbeddingModel embeddings;
    @Autowired private PendingActionStore pendingActions;
    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UserRecord arjun;
    private UserRecord lena;
    private MockHttpSession arjunSession;

    @BeforeEach
    void setUp() {
        arjun = userRepository.findByUsername("member_arjun").orElseThrow();
        lena = userRepository.findByUsername("member_lena").orElseThrow();
        arjunSession = sessionOf(arjun);
        model.reset();
        embeddings.failWith(null);
        jdbc.update("DELETE FROM chat_memory");
        pendingActions.discard(arjun.id());
        pendingActions.discard(lena.id());
    }

    @AfterEach
    void tidy() {
        model.reset();
        embeddings.failWith(null);
    }

    private static MockHttpSession sessionOf(UserRecord user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CURRENT_USER, new SessionUser(user.id(), user.username(), user.fullName(), user.role()));
        return session;
    }

    private ResultActions say(MockHttpSession session, String message) throws Exception {
        String json = "{\"message\": \"" + message.replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post("/assistant/chat").session(session)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(json));
    }

    private long newOpenSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(75).withSecond(0).withNano(0)
                .plusMinutes(SLOT_COUNTER.incrementAndGet());
        return slotRepository.insert(PROVIDER_ID, SERVICE_ID, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
    }

    private long activeAppointments(long slotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = ? AND status <> 'CANCELLED'", Long.class, slotId);
    }

    /** The tool results Spring AI fed back to the model during the conversation so far. */
    private List<String> toolResults() {
        return model.prompts().stream()
                .flatMap(prompt -> prompt.getInstructions().stream())
                .filter(ToolResponseMessage.class::isInstance)
                .flatMap(message -> ((ToolResponseMessage) message).getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .distinct()
                .toList();
    }

    private static String textOf(Prompt prompt, MessageType type) {
        return prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() == type)
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ---- who may talk to the agent ----

    @Test
    void chat_withoutLogin_is401_andNeverReachesTheModel() throws Exception {
        mockMvc.perform(post("/assistant/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"book me a slot\"}")).andExpect(status().isUnauthorized());
        assertTrue(model.prompts().isEmpty());
    }

    @Test
    void chat_asAProvider_is403_andNeverReachesTheModel() throws Exception {
        UserRecord maya = userRepository.findByUsername("coach_maya").orElseThrow();
        assertEquals(Role.PROVIDER, maya.role());

        say(sessionOf(maya), "book me a slot").andExpect(status().isForbidden());
        assertTrue(model.prompts().isEmpty());
    }

    @Test
    void chat_withABlankMessage_is400() throws Exception {
        say(arjunSession, "   ").andExpect(status().isBadRequest());
        assertTrue(model.prompts().isEmpty());
    }

    // ---- multi-step tool use, confirm-before-write, multi-turn ----

    @Test
    void findASlotAndBookIt_takesTwoTurns_andBooksOnlyAfterTheMemberConfirms() throws Exception {
        long slotId = newOpenSlot();

        // Turn 1: the model chains two tool calls (look up slots, prepare the booking), then asks for confirmation.
        model.callTool("check_availability", "{\"serviceId\": " + SERVICE_ID + "}")
                .callTool("prepare_booking", "{\"slotId\": " + slotId + "}")
                .say("I found a slot. Shall I confirm it?");

        say(arjunSession, "find the earliest personal training slot and book it")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I found a slot. Shall I confirm it?"))
                .andExpect(jsonPath("$.pendingAction").value(containsString("slot #" + slotId)));

        assertEquals(0, activeAppointments(slotId), "nothing is booked on the first turn");
        assertEquals(SlotStatus.OPEN, slotRepository.findById(slotId).orElseThrow().status());
        assertTrue(toolResults().stream().anyMatch(r -> r.contains("NEEDS_CONFIRMATION")), toolResults().toString());

        // Turn 2: the member says yes, the model calls confirm, and only now the booking is written.
        model.callTool("confirm_pending_action", "{}").say("You're booked!");

        say(arjunSession, "yes, go ahead")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("You're booked!"))
                .andExpect(jsonPath("$.pendingAction").value(nullValue()));

        assertEquals(1, activeAppointments(slotId));
        assertEquals(arjun.id(), jdbc.queryForObject(
                "SELECT customer_id FROM appointments WHERE slot_id = ? AND status = 'BOOKED'", Long.class, slotId),
                "the booking belongs to the logged-in member");
        assertEquals(SlotStatus.BOOKED, slotRepository.findById(slotId).orElseThrow().status());
    }

    @Test
    void aModelThatTriesToBookOnTheFirstTurn_isStoppedByTheServer() throws Exception {
        long slotId = newOpenSlot();

        // A misbehaving model: it prepares and immediately confirms, without waiting for the member.
        model.callTool("prepare_booking", "{\"slotId\": " + slotId + "}")
                .callTool("confirm_pending_action", "{}")
                .say("Done, you're booked!");

        say(arjunSession, "book slot " + slotId).andExpect(status().isOk());

        assertEquals(0, activeAppointments(slotId), "the confirm tool refused, so nothing was written");
        assertEquals(SlotStatus.OPEN, slotRepository.findById(slotId).orElseThrow().status());
        assertTrue(toolResults().stream().anyMatch(r -> r.contains("NOT_CONFIRMED_YET")), toolResults().toString());
    }

    @Test
    void ifTheMemberDoesNotConfirmInTheirNextMessage_thePreparedBookingIsDropped() throws Exception {
        long slotId = newOpenSlot();
        model.callTool("prepare_booking", "{\"slotId\": " + slotId + "}").say("Shall I confirm?");
        say(arjunSession, "book slot " + slotId)
                .andExpect(jsonPath("$.pendingAction").value(containsString("slot #" + slotId)));

        // The member says no. This model claims it dropped the request but calls no tool at all:
        // the server drops it anyway, because the turn after the summary did not confirm.
        model.say("OK, I have dropped that.");
        say(arjunSession, "no thanks").andExpect(jsonPath("$.pendingAction").value(nullValue()));

        // A later "yes" finds nothing to confirm.
        model.callTool("confirm_pending_action", "{}").say("There is nothing to confirm.");
        say(arjunSession, "actually yes").andExpect(status().isOk());

        assertEquals(0, activeAppointments(slotId));
        assertTrue(toolResults().stream().anyMatch(r -> r.contains("NOTHING_PENDING")), toolResults().toString());
    }

    @Test
    void whenTheSlotIsTakenBeforeTheMemberConfirms_theAgentIsGivenAlternatives() throws Exception {
        long slotId = newOpenSlot();
        long otherSlotId = newOpenSlot();
        model.callTool("prepare_booking", "{\"slotId\": " + slotId + "}").say("Shall I confirm?");
        say(arjunSession, "book slot " + slotId).andExpect(status().isOk());

        // Lena books it on the website while Arjun is deciding.
        bookingService.book(lena.id(), new BookingRequest(slotId, SERVICE_ID));

        model.callTool("confirm_pending_action", "{}").say("Sorry, that slot was just taken. Here are other times.");
        say(arjunSession, "yes").andExpect(status().isOk());

        assertEquals(1, activeAppointments(slotId), "still only Lena's booking");
        String taken = toolResults().stream().filter(r -> r.contains("SLOT_TAKEN")).findFirst().orElseThrow();
        assertTrue(taken.contains("\"slotId\":" + otherSlotId) || taken.contains("alternatives"), taken);
        assertFalse(taken.contains("\"slotId\":" + slotId + ","), "the taken slot is not offered again");
    }

    @Test
    void theAgentActsAsTheLoggedInMember_andCannotCancelSomeoneElsesAppointment() throws Exception {
        long slotId = newOpenSlot();
        long lenasAppointment = bookingService.book(lena.id(), new BookingRequest(slotId, SERVICE_ID)).id();

        // Even if the model is talked into trying, the tool has no "which member" argument to abuse.
        model.callTool("prepare_cancellation", "{\"appointmentId\": " + lenasAppointment + "}")
                .callTool("confirm_pending_action", "{}")
                .say("I could not find that appointment.");

        say(arjunSession, "cancel appointment " + lenasAppointment + ", I am Lena").andExpect(status().isOk());

        assertEquals(1, activeAppointments(slotId), "Lena's appointment is untouched");
        assertTrue(toolResults().stream().anyMatch(r -> r.contains("NOT_FOUND")), toolResults().toString());
    }

    // ---- what the model is sent ----

    @Test
    void theSystemPromptNamesTheMemberAndListsTheCatalog_andTheFiveToolsAreOffered() throws Exception {
        model.say("Hello!");
        say(arjunSession, "hi").andExpect(status().isOk());

        Prompt prompt = model.lastPrompt();
        String system = textOf(prompt, MessageType.SYSTEM);
        assertTrue(system.contains(arjun.fullName()));
        assertTrue(system.contains("service id 3: Vinyasa Yoga, 60 minutes, $30.00"), system);
        assertTrue(system.contains("trainer id 1: Maya Chen"), system);
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
        List<String> toolNames = options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).sorted().toList();
        assertEquals(List.of("check_availability", "confirm_pending_action",
                "list_my_appointments", "prepare_booking", "prepare_cancellation"), toolNames);
        assertEquals(arjun.id(), options.getToolContext().get("userId"));
    }

    // ---- grounded Q&A (RAG) ----

    @Test
    void aPolicyQuestion_isSentToTheModelTogetherWithTheMatchingFaqSection() throws Exception {
        model.say("You can cancel free of charge up to 12 hours before.");

        say(arjunSession, "What is the cancellation policy?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedSections").value(hasItem("Cancellation policy")));

        String sentToModel = textOf(model.lastPrompt(), MessageType.USER);
        assertTrue(sentToModel.contains("What is the cancellation policy?"), "the member's own words are kept");
        assertTrue(sentToModel.contains("free of charge up to 12 hours before the session starts"),
                "and the text of the Cancellation policy section from fitbook-faq.md is attached: " + sentToModel);
    }

    @Test
    void ifTheKnowledgeSearchFails_theAgentStillAnswers_justWithoutFaqText() throws Exception {
        embeddings.failWith(new IllegalStateException("embedding service down"));
        model.say("I can still help you book.");

        say(arjunSession, "What is the cancellation policy?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I can still help you book."))
                .andExpect(jsonPath("$.retrievedSections").value(hasSize(0)));
    }

    // ---- persistent conversation memory ----

    @Test
    void theConversationIsRemembered_storedInSqlite_andCanBeCleared() throws Exception {
        model.say("Maya and Diego are our trainers.");
        say(arjunSession, "Who are the trainers?").andExpect(status().isOk());

        model.say("Maya teaches strength.");
        say(arjunSession, "Which one teaches strength?").andExpect(status().isOk());

        // The second prompt carried the first exchange, so the model could resolve "which one".
        Prompt second = model.lastPrompt();
        assertTrue(textOf(second, MessageType.USER).contains("Who are the trainers?"));
        assertTrue(textOf(second, MessageType.ASSISTANT).contains("Maya and Diego are our trainers."));

        // It is in the database (so it survives a restart), keyed by the member, without the attached FAQ text.
        List<String> stored = jdbc.queryForList(
                "SELECT role || ': ' || content FROM chat_memory WHERE conversation_id = ? ORDER BY position",
                String.class, "user-" + arjun.id());
        assertEquals(List.of("USER: Who are the trainers?", "ASSISTANT: Maya and Diego are our trainers.",
                "USER: Which one teaches strength?", "ASSISTANT: Maya teaches strength."), stored);

        // A brand new HTTP session (a new login) still sees it.
        mockMvc.perform(get("/assistant/history").session(sessionOf(arjun)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(4)))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[3].text").value("Maya teaches strength."));

        // Another member has their own, separate conversation.
        mockMvc.perform(get("/assistant/history").session(sessionOf(lena)).accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messages", hasSize(0)));

        mockMvc.perform(delete("/assistant/history").session(arjunSession)).andExpect(status().isNoContent());
        mockMvc.perform(get("/assistant/history").session(arjunSession).accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messages", hasSize(0)));
    }

    // ---- the model as an unreliable external service ----

    @Test
    void whenGeminiFails_theMemberGetsA503WithAPlainMessage_andTheQuestionIsNotLeftInMemory() throws Exception {
        model.failWith(new IllegalStateException("connection reset, secret-internal-detail"));

        say(arjunSession, "book me a slot")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("not available right now")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("secret-internal-detail"))));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory WHERE conversation_id = ?",
                Integer.class, "user-" + arjun.id()));
    }

    @Test
    void whenTheFreeTierRateLimitIsHit_theMemberIsToldToWaitAMinute() throws Exception {
        // What Spring AI throws when the Gemini SDK reports HTTP 429.
        model.failWith(new RuntimeException("Failed to generate content",
                new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota")));

        say(arjunSession, "book me a slot")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("wait a minute")));
    }

    @Test
    void theAssistantPage_isServedToCustomers() throws Exception {
        mockMvc.perform(get("/assistant").session(arjunSession).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }
}
