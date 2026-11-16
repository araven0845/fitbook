package com.fitbook.ai;

import com.fitbook.ai.knowledge.KnowledgeBase;
import com.fitbook.testsupport.HashingEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The retrieval half of RAG: chunking the FAQ file, searching it, and falling back when embedding fails. */
class KnowledgeBaseTest {

    private final HashingEmbeddingModel embeddings = new HashingEmbeddingModel();
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new KnowledgeBase(embeddings, new ClassPathResource("knowledge/fitbook-faq.md"),
                new AiAvailability("a-key"), Clock.systemDefaultZone());
    }

    private List<String> sectionsFor(String question, int topK) {
        return knowledgeBase.similaritySearch(SearchRequest.builder().query(question).topK(topK).build()).stream()
                .map(d -> (String) d.getMetadata().get(KnowledgeBase.SECTION)).toList();
    }

    @Test
    void theFaqFileIsLoadedOnStartup_oneChunkPerSection() {
        knowledgeBase.loadOnStartup();

        assertTrue(knowledgeBase.isLoaded());
        List<Document> all = knowledgeBase.similaritySearch(SearchRequest.builder().query("fitbook").topK(50).similarityThresholdAll().build());
        assertEquals(12, all.size());
        assertTrue(all.stream().allMatch(d -> "fitbook-faq.md".equals(d.getMetadata().get(KnowledgeBase.SOURCE))));
        assertTrue(all.stream().noneMatch(d -> d.getText().contains("The content is synthetic")), "the preamble is not a chunk");
    }

    @Test
    void aQuestionRetrievesTheSectionThatAnswersIt() {
        assertEquals("Cancellation policy", sectionsFor("What is the cancellation policy? Can I cancel free of charge?", 1).get(0));
        assertEquals("Opening hours", sectionsFor("What are the opening hours on Saturday?", 1).get(0));
        assertEquals("What to bring", sectionsFor("What should I bring? Do I need a towel and a water bottle?", 1).get(0));
        assertEquals("Services and prices", sectionsFor("How much do the services cost, what are the prices?", 1).get(0));
    }

    @Test
    void theFileIsLoadedLazily_ifItWasNotLoadedAtStartup() {
        assertFalse(knowledgeBase.isLoaded());
        assertEquals(3, sectionsFor("parking", 3).size());
        assertTrue(knowledgeBase.isLoaded());
    }

    @Test
    void withoutAnApiKey_nothingIsEmbeddedAtStartup() {
        KnowledgeBase unconfigured = new KnowledgeBase(embeddings, new ClassPathResource("knowledge/fitbook-faq.md"),
                new AiAvailability(""), Clock.systemDefaultZone());

        unconfigured.loadOnStartup();

        assertFalse(unconfigured.isLoaded());
        assertEquals(0, embeddings.calls(), "the embedding model must not be called without a key");
    }

    @Test
    void ifEmbeddingFails_aSearchReturnsNothingInsteadOfThrowing() {
        knowledgeBase.loadOnStartup();
        embeddings.failWith(new IllegalStateException("embedding service down"));

        assertTrue(sectionsFor("cancellation policy", 3).isEmpty());

        embeddings.failWith(null);
        assertFalse(sectionsFor("cancellation policy", 3).isEmpty(), "and it recovers when the service is back");
    }

    @Test
    void ifLoadingFails_searchesReturnNothing() {
        embeddings.failWith(new IllegalStateException("embedding service down"));

        knowledgeBase.loadOnStartup();

        assertFalse(knowledgeBase.isLoaded());
        assertTrue(sectionsFor("cancellation policy", 3).isEmpty());
    }
}
