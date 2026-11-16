package com.fitbook.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Answers one question: was a Gemini API key supplied? The app must still start, pass its tests
 * and serve normal bookings without one, so the AI features check this and degrade politely.
 * Only the yes/no answer is kept; the key itself is not stored in this object.
 */
@Component
public class AiAvailability {

    private final boolean configured;

    public AiAvailability(@Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        this.configured = StringUtils.hasText(apiKey);
    }

    public boolean isConfigured() {
        return configured;
    }
}
