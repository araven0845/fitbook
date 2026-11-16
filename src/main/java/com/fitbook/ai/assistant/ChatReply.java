package com.fitbook.ai.assistant;

import java.util.List;

/**
 * One assistant turn.
 *
 * @param reply             the assistant's text
 * @param pendingAction     summary of a booking or cancellation waiting for the member's yes, or null
 * @param retrievedSections titles of the FAQ sections retrieved for this message (the RAG context)
 */
public record ChatReply(String reply, String pendingAction, List<String> retrievedSections) {}
