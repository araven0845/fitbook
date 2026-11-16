package com.fitbook.testsupport;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the two model beans in every test context, in place of the Gemini-backed ones that
 * src/test/resources/config/application.yml switches off. It lives under com.fitbook on the test
 * classpath, so component scanning picks it up automatically. The rest of the AI wiring
 * (ChatClient, advisors, tools, memory, knowledge base, reminder agent) is the production code.
 */
@Configuration
public class StubAiConfig {

    @Bean
    public ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }

    @Bean
    public HashingEmbeddingModel hashingEmbeddingModel() {
        return new HashingEmbeddingModel();
    }
}
