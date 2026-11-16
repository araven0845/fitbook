package com.fitbook.ai.knowledge;

import com.fitbook.ai.AiAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The retrieval half of RAG. Reads the policy and FAQ file, cuts it into one chunk per "## "
 * section, and stores the chunks with their embeddings in Spring AI's in-memory SimpleVectorStore.
 * A question is answered by embedding it and returning the closest sections.
 *
 * <p>It is itself a VectorStore, so the QuestionAnswerAdvisor can use it directly. The wrapper adds
 * two things the plain store lacks: loading (on startup, or lazily if that failed) and a fallback.
 * If the embedding call fails, a search returns no sections instead of throwing, so the assistant
 * can still book and cancel while the knowledge base is unavailable.
 */
@Component
public class KnowledgeBase implements VectorStore {

    public static final String SECTION = "section";
    public static final String SOURCE = "source";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final Duration RELOAD_COOLDOWN = Duration.ofSeconds(60);

    private final SimpleVectorStore store;
    private final Resource knowledgeFile;
    private final AiAvailability availability;
    private final Clock clock;

    private volatile boolean loaded;
    private Instant lastLoadAttempt;

    public KnowledgeBase(EmbeddingModel embeddingModel,
                         @Value("classpath:knowledge/fitbook-faq.md") Resource knowledgeFile,
                         AiAvailability availability, Clock clock) {
        this.store = SimpleVectorStore.builder(embeddingModel).build();
        this.knowledgeFile = knowledgeFile;
        this.availability = availability;
        this.clock = clock;
    }

    /** Ingest once the app is up. Without an API key there is nothing to embed with, so skip. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        if (!availability.isConfigured()) {
            log.info("Knowledge base not loaded: no Gemini API key configured");
            return;
        }
        ensureLoaded();
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** Splits the markdown file into one document per "## " section (the text above the first one is a preamble). */
    List<Document> readSections() {
        String text;
        try {
            text = knowledgeFile.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the knowledge file", e);
        }
        String source = knowledgeFile.getFilename();
        List<Document> sections = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (line.startsWith("## ")) {
                addSection(sections, title, body, source);
                title = line.substring(3).trim();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        addSection(sections, title, body, source);
        return sections;
    }

    private void addSection(List<Document> sections, String title, StringBuilder body, String source) {
        if (title == null || body.toString().isBlank()) {
            return;
        }
        sections.add(new Document(title + "\n" + body.toString().strip(), Map.of(SECTION, title, SOURCE, source)));
    }

    private synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        Instant now = clock.instant();
        if (lastLoadAttempt != null && now.isBefore(lastLoadAttempt.plus(RELOAD_COOLDOWN))) {
            return false;
        }
        lastLoadAttempt = now;
        try {
            List<Document> sections = readSections();
            store.add(sections);
            loaded = true;
            log.info("Knowledge base loaded sections={} source={}", sections.size(), knowledgeFile.getFilename());
            return true;
        } catch (RuntimeException e) {
            log.warn("Knowledge base could not be loaded, answers will not be grounded until it is: {}", e.getMessage());
            return false;
        }
    }

    /** Never throws: on any failure the caller simply gets no sections. */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (!ensureLoaded()) {
            return List.of();
        }
        try {
            List<Document> hits = store.similaritySearch(request);
            log.info("Knowledge retrieved sections={}", hits.stream()
                    .map(d -> "%s (%.2f)".formatted(d.getMetadata().get(SECTION), d.getScore() == null ? 0.0 : d.getScore()))
                    .toList());
            return hits;
        } catch (RuntimeException e) {
            log.warn("Knowledge search failed, continuing without retrieved sections: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void add(List<Document> documents) {
        store.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        store.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        store.delete(filterExpression);
    }
}
