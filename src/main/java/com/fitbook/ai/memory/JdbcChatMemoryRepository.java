package com.fitbook.ai.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent conversation memory: Spring AI's ChatMemoryRepository contract implemented with
 * hand-written SQL over the chat_memory table, like every other repository in this project.
 * Because the rows live in SQLite, the assistant still remembers a conversation after a restart
 * or a new login. Only user and assistant text is kept; tool calls are not replayed.
 */
@Repository
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM chat_memory ORDER BY conversation_id", String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String sql = "SELECT role, content FROM chat_memory WHERE conversation_id = ? ORDER BY position";
        return jdbcTemplate.query(sql, (rs, rowNum) -> toMessage(rs.getString("role"), rs.getString("content")), conversationId);
    }

    /** Spring AI hands over the whole window each time, so the conversation is replaced in one transaction. */
    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
        List<Object[]> rows = new ArrayList<>();
        for (Message message : messages) {
            String role = roleOf(message.getMessageType());
            String content = message.getText();
            if (role == null || content == null || content.isBlank()) {
                continue;
            }
            rows.add(new Object[]{conversationId, rows.size(), role, content});
        }
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO chat_memory (conversation_id, position, role, content) VALUES (?, ?, ?, ?)", rows);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
    }

    private static String roleOf(MessageType type) {
        return switch (type) {
            case USER -> "USER";
            case ASSISTANT -> "ASSISTANT";
            case SYSTEM -> "SYSTEM";
            default -> null;
        };
    }

    private static Message toMessage(String role, String content) {
        return switch (role) {
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }
}
