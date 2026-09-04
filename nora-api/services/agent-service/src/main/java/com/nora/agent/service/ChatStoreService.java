package com.nora.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for chat sessions and messages ({@code schema_agent}).
 * Steps and sources are stored as JSON strings.
 */
@Service
public class ChatStoreService {

    private static final TypeReference<List<ChatStepDto>> STEP_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<CitationDto>> SOURCE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatStoreService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Creates a session row; idempotent for an existing id. */
    public void ensureSession(String sessionId, String title) {
        jdbcTemplate.update(
                "INSERT INTO chat_session (id, title) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                sessionId, title);
    }

    /** Saves one message with its steps/sources snapshots. */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (id, session_id, role, content, steps, sources) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sessionId, role, content,
                toJson(steps), toJson(sources));
    }

    /** Loads all messages of a session in chronological order. */
    public List<StoredMessage> loadMessages(String sessionId) {
        return jdbcTemplate.query(
                "SELECT role, content, steps, sources, created_at FROM chat_message WHERE session_id = ? ORDER BY created_at, id",
                (rs, rowNum) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        fromJson(rs.getString("steps"), STEP_LIST),
                        fromJson(rs.getString("sources"), SOURCE_LIST)),
                sessionId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private <T> List<T> fromJson(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** One persisted message row. */
    public record StoredMessage(
            String role,
            String content,
            List<ChatStepDto> steps,
            List<CitationDto> sources
    ) {
    }
}
