package com.fitbook.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Stands in for Gemini in tests. A test queues the "model's" next moves (call this tool, then say
 * this), and the real Spring AI machinery does the rest: when a scripted move is a tool call, the
 * real ToolCallingManager runs the real @Tool method with the real tool context and the result is
 * fed back in, exactly the loop GoogleGenAiChatModel runs in production. So everything except the
 * model's judgement is exercised, with no network and no API key.
 */
public class ScriptedChatModel implements ChatModel {

    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    private final Deque<Function<Prompt, AssistantMessage>> script = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private RuntimeException failure;

    /** Forget everything queued and recorded; call before each test. */
    public synchronized void reset() {
        script.clear();
        prompts.clear();
        failure = null;
    }

    /** Next model move: answer with plain text. */
    public synchronized ScriptedChatModel say(String text) {
        script.add(prompt -> new AssistantMessage(text));
        return this;
    }

    /** Next model move: answer with text computed from the prompt it was sent. */
    public synchronized ScriptedChatModel reply(Function<Prompt, String> answer) {
        script.add(prompt -> new AssistantMessage(answer.apply(prompt)));
        return this;
    }

    /** Next model move: call one tool with these JSON arguments. */
    public synchronized ScriptedChatModel callTool(String toolName, String jsonArguments) {
        script.add(prompt -> AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + toolName, "function", toolName, jsonArguments)))
                .build());
        return this;
    }

    /** Every call fails with this error until reset(), like an outage or a rate limit. */
    public synchronized void failWith(RuntimeException error) {
        this.failure = error;
    }

    /** Every prompt the model was sent, including the follow-ups that carry tool results. */
    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized Prompt lastPrompt() {
        return prompts.get(prompts.size() - 1);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        AssistantMessage move;
        synchronized (this) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            Function<Prompt, AssistantMessage> next = script.poll();
            move = next == null ? new AssistantMessage("(nothing scripted)") : next.apply(prompt);
        }
        ChatResponse response = new ChatResponse(List.of(new Generation(move)));
        if (move.hasToolCalls()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            return call(new Prompt(result.conversationHistory(), prompt.getOptions()));
        }
        return response;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }
}
