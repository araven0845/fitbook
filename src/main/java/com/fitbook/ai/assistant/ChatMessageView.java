package com.fitbook.ai.assistant;

/** One remembered message, for showing the saved conversation again. `role` is "user" or "assistant". */
public record ChatMessageView(String role, String text) {}
