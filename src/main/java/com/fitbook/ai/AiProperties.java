package com.fitbook.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * FitBook's own AI settings. The Gemini key and model names are standard Spring AI properties
 * (spring.ai.google.genai.*), not part of this record, so the key is never held or printed here.
 *
 * @param requestTimeout    hard limit for one call to the Gemini API
 * @param memoryMaxMessages how many past messages the assistant keeps per member
 * @param confirmationTtl   how long a prepared booking or cancellation waits for the member's "yes"
 */
@ConfigurationProperties(prefix = "fitbook.ai")
public record AiProperties(
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("20") int memoryMaxMessages,
        @DefaultValue("10m") Duration confirmationTtl,
        @DefaultValue Rag rag,
        @DefaultValue Reminder reminder
) {

    /**
     * @param topK                how many FAQ sections to retrieve per question
     * @param similarityThreshold sections scoring below this are not attached to the prompt
     */
    public record Rag(
            @DefaultValue("3") int topK,
            @DefaultValue("0.55") double similarityThreshold
    ) {}

    /**
     * @param schedulingEnabled turns the timer on or off (the manual trigger works either way)
     * @param interval          pause between two scheduled runs
     * @param initialDelay      wait after startup before the first run
     * @param lookahead         remind for appointments starting within this window
     * @param maxPerRun         cap on reminders per run, to stay inside the free-tier rate limit
     */
    public record Reminder(
            @DefaultValue("true") boolean schedulingEnabled,
            @DefaultValue("15m") Duration interval,
            @DefaultValue("1m") Duration initialDelay,
            @DefaultValue("24h") Duration lookahead,
            @DefaultValue("10") int maxPerRun
    ) {}
}
