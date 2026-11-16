package com.fitbook.ai;

import com.fitbook.FitbookIntegrationTest;
import com.fitbook.ai.memory.JdbcChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-written SQL behind persistent conversation memory, against a real SQLite file. */
@FitbookIntegrationTest
class JdbcChatMemoryRepositoryTest {

    @Autowired private JdbcChatMemoryRepository repository;
    @Autowired private ChatMemory chatMemory;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_memory");
    }

    @Test
    void messagesComeBackInOrder_withTheirRoles() {
        repository.saveAll("c1", List.of(new UserMessage("hi"), new AssistantMessage("hello"), new UserMessage("book yoga")));

        List<Message> loaded = repository.findByConversationId("c1");

        assertEquals(List.of("hi", "hello", "book yoga"), loaded.stream().map(Message::getText).toList());
        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER),
                loaded.stream().map(Message::getMessageType).toList());
    }

    @Test
    void saveAll_replacesTheConversation_andLeavesOthersAlone() {
        repository.saveAll("c1", List.of(new UserMessage("old 1"), new AssistantMessage("old 2")));
        repository.saveAll("c2", List.of(new UserMessage("other member")));

        repository.saveAll("c1", List.of(new UserMessage("new")));

        assertEquals(List.of("new"), repository.findByConversationId("c1").stream().map(Message::getText).toList());
        assertEquals(1, repository.findByConversationId("c2").size());
        assertEquals(List.of("c1", "c2"), repository.findConversationIds());
    }

    @Test
    void blankMessagesAreNotStored() {
        repository.saveAll("c1", List.of(new UserMessage("question"), new AssistantMessage("")));
        assertEquals(1, repository.findByConversationId("c1").size());
    }

    @Test
    void delete_removesOnlyThatConversation() {
        repository.saveAll("c1", List.of(new UserMessage("a")));
        repository.saveAll("c2", List.of(new UserMessage("b")));

        repository.deleteByConversationId("c1");

        assertTrue(repository.findByConversationId("c1").isEmpty());
        assertEquals(1, repository.findByConversationId("c2").size());
    }

    @Test
    void theMemoryWindowKeepsOnlyTheLatestMessages() {
        // The ChatMemory bean wraps this repository in a sliding window (fitbook.ai.memory-max-messages = 20).
        List<Message> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(new UserMessage("message " + i));
        }
        chatMemory.add("c1", many);

        List<Message> kept = chatMemory.get("c1");
        assertEquals(20, kept.size());
        assertEquals("message 6", kept.get(0).getText());
        assertEquals("message 25", kept.get(19).getText());
    }
}
