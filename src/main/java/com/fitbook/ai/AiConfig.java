package com.fitbook.ai;

import com.fitbook.ai.knowledge.KnowledgeBase;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires Spring AI to the Gemini API and builds the two ChatClients the agents use.
 * Only the api-key is configured (never project-id or location, which would switch the client to
 * Vertex AI mode and reject a free Google AI Studio key).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /** Bean names, so each agent can ask for its own client. */
    public static final String ASSISTANT_CLIENT = "assistantChatClient";
    public static final String REMINDER_CLIENT = "reminderChatClient";

    /**
     * How retrieved FAQ text is attached to the member's message. {query} is the message and
     * {question_answer_context} is the retrieved sections; both are filled in by the advisor.
     */
    static final String RAG_TEMPLATE = """
            {query}

            Studio knowledge (sections retrieved from the FitBook policy and FAQ file for the message above; \
            may be empty or unrelated):
            ---------------------
            {question_answer_context}
            ---------------------
            Use these sections only when the message asks about studio policies, hours, prices, services or rules, \
            and answer such questions from them alone. For availability, bookings and cancellations use your tools.
            """;

    /**
     * The one Gemini client, shared by chat and embeddings. Declared here (instead of leaving it to
     * auto-configuration) for two reasons: a hard request timeout plus a small retry budget, and so
     * the app still starts when no key is set. The placeholder key is never sent anywhere because
     * every AI entry point checks AiAvailability first.
     */
    @Bean
    public Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key:}") String apiKey, AiProperties properties) {
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout((int) properties.requestTimeout().toMillis())
                // Retry brief server-side trouble only. A 429 (rate limit) is not retried: waiting a few
                // seconds does not help against a per-minute quota, it only makes the member wait longer.
                .retryOptions(HttpRetryOptions.builder().attempts(3).initialDelay(1.0).maxDelay(4.0)
                        .httpStatusCodes(500, 502, 503, 504).build())
                .build();
        return Client.builder()
                .apiKey(StringUtils.hasText(apiKey) ? apiKey : "not-configured")
                .httpOptions(httpOptions)
                .build();
    }

    @Bean
    public GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(Client googleGenAiClient) {
        return GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(googleGenAiClient).build();
    }

    /** Conversation memory: a sliding window of the latest messages, stored in SQLite. */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, AiProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.memoryMaxMessages())
                .build();
    }

    /**
     * Agent 1's client. Two advisors run around every call: the memory advisor adds the earlier
     * conversation and records the new messages, then the question-answer advisor searches the
     * knowledge base with the member's message and attaches the matching FAQ sections (RAG).
     */
    @Bean(ASSISTANT_CLIENT)
    public ChatClient assistantChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                          KnowledgeBase knowledgeBase, AiProperties properties) {
        SearchRequest search = SearchRequest.builder()
                .topK(properties.rag().topK())
                .similarityThreshold(properties.rag().similarityThreshold())
                .build();
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(knowledgeBase)
                                .searchRequest(search)
                                .promptTemplate(new PromptTemplate(RAG_TEMPLATE))
                                .build())
                .build();
    }

    /** Agent 2's client: no memory and no tools, it only writes reminder text. */
    @Bean(REMINDER_CLIENT)
    public ChatClient reminderChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
