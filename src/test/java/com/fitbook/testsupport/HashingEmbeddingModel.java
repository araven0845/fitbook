package com.fitbook.testsupport;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny local embedding model for tests: each word is hashed into one of 512 buckets and the
 * vector is normalised, so texts that share words score as similar. It is nowhere near as good as
 * Gemini's embeddings, but it is deterministic, needs no network, and is enough to prove the RAG
 * plumbing (ingest, embed, search, attach to the prompt) works.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 512;
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "of", "to", "is", "are", "in",
            "on", "for", "it", "do", "does", "can", "i", "my", "what", "how", "with", "at", "be", "by", "from", "if",
            "not", "no", "that", "this", "their", "there", "any", "up", "before", "who", "when", "you", "your", "me");

    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;

    /** How many times the model was asked to embed something. */
    public int calls() {
        return calls.get();
    }

    /** Every call fails with this error until cleared with null, like an embedding outage. */
    public void failWith(RuntimeException error) {
        this.failure = error;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vectorOf(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            // Crude stemming so "cancel", "cancelling" and "cancellation" land in the same bucket.
            String stem = word.length() > 6 ? word.substring(0, 6) : word;
            vector[Math.floorMod(stem.hashCode(), DIMENSIONS)] += 1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }
}
