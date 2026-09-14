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

    /** 占位标题保留的用户消息字数（AI 标题就绪后会被覆盖）。 */
    private static final int PLACEHOLDER_CHARS = 20;

    /** 标题落库硬上限：列是 VARCHAR(255)，留出余量避免超长写入直接抛错。 */
    private static final int MAX_TITLE_CHARS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatStoreService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a session row; idempotent for an existing id.
     *
     * <p>{@code title} 只在该行首次创建时生效（{@code ON CONFLICT DO NOTHING}），
     * 因此传入的是**占位标题**——由 {@link #placeholderTitle(String)} 取首条消息
     * 开头若干字生成，随后由 AI 生成的短标题覆盖（见 {@link #updateTitle}）。
     *
     * @return true 当且仅当本次真的创建了新行（= 这是该会话的第一轮）。调用方
     *         据此决定是否触发 AI 起标题，避免后续轮次覆盖已经起好的名字。
     */
    public boolean ensureSession(String sessionId, String title) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO chat_session (id, title) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                sessionId, clampTitle(title));
        return inserted > 0;
    }

    /**
     * 首轮占位标题：用户消息开头若干字 + 省略号。
     *
     * <p>为什么不再直接存整条原文：标题只用于侧栏辨识，长消息既显示不下又会撑爆
     * 列宽——历史实现把原始 {@code content} 直接当 title 写入，超过 255 字的消息
     * 会让会话创建直接失败（实测 {@code value too long for type character varying(255)}）。
     * 首轮先用短占位保证「立刻有名字可看」，AI 标题异步就绪后替换。
     */
    public static String placeholderTitle(String content) {
        if (content == null) {
            return "";
        }
        // 折叠所有空白（含换行）：标题必须是单行，否则侧栏条目会撑高
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= PLACEHOLDER_CHARS ? flat : flat.substring(0, PLACEHOLDER_CHARS) + "…";
    }

    /** Overwrites a session's title (AI 生成标题就绪时调用)。 */
    public void updateTitle(String sessionId, String title) {
        jdbcTemplate.update("UPDATE chat_session SET title = ?, title_generated = TRUE WHERE id = ?",
                clampTitle(title), sessionId);
    }

    /** 标题列宽兜底：超长直接截断，绝不让写标题把会话创建/更新带崩。 */
    private static String clampTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.length() <= MAX_TITLE_CHARS ? title : title.substring(0, MAX_TITLE_CHARS) + "…";
    }

    /** Saves one message with its steps/sources snapshots. */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (id, session_id, role, content, steps, sources) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sessionId, role, content,
                toJson(steps), toJson(sources));
    }

    /** Loads all messages of a session in chronological order (soft-deleted rows excluded). */
    public List<StoredMessage> loadMessages(String sessionId) {
        List<StoredMessage> loaded = jdbcTemplate.query(
                "SELECT role, content, steps, sources, created_at FROM chat_message WHERE session_id = ? AND deleted_at IS NULL ORDER BY created_at, id",
                (rs, rowNum) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        fromJson(rs.getString("steps"), STEP_LIST),
                        fromJson(rs.getString("sources"), SOURCE_LIST),
                        rs.getObject("created_at", java.time.LocalDateTime.class)),
                sessionId);
        // steps 按 (id → 状态) 追加式存储(running 先行、终态覆盖),恢复时合并去重,
        // 并丢弃没有终态的悬挂 running(会话中断残留)
        for (int i = 0; i < loaded.size(); i++) {
            List<ChatStepDto> steps = loaded.get(i).steps();
            if (steps == null || steps.isEmpty()) continue;
            loaded.set(i, new StoredMessage(loaded.get(i).role(), loaded.get(i).content(),
                    mergeSteps(steps), loaded.get(i).sources(), loaded.get(i).createdAt()));
        }
        return loaded;
    }

    /** Collapses append-only step rows: terminal status wins; dangling running steps are dropped. */
    static List<ChatStepDto> mergeSteps(List<ChatStepDto> steps) {
        java.util.LinkedHashMap<String, ChatStepDto> byId = new java.util.LinkedHashMap<>();
        for (ChatStepDto step : steps) {
            ChatStepDto prev = byId.get(step.id());
            boolean terminal = !"running".equals(step.status()) && !"pending".equals(step.status());
            if (prev == null || terminal) {
                byId.put(step.id(), step);
            }
        }
        return byId.values().stream()
                .filter(s -> !"running".equals(s.status()) && !"pending".equals(s.status()))
                .toList();
    }

    /** Lists live sessions with message counts, most recently active first (soft-deleted excluded). */
    public List<SessionSummary> listSessions() {
        return jdbcTemplate.query(
                """
                SELECT s.id, s.title, s.title_generated, s.created_at, count(m.id) AS message_count,
                       max(m.created_at) AS last_activity
                FROM chat_session s
                LEFT JOIN chat_message m ON m.session_id = s.id AND m.deleted_at IS NULL
                WHERE s.deleted_at IS NULL
                GROUP BY s.id, s.title, s.title_generated, s.created_at
                ORDER BY max(m.created_at) DESC NULLS LAST, s.created_at DESC
                """,
                (rs, rowNum) -> new SessionSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getBoolean("title_generated"),
                        rs.getInt("message_count"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("last_activity")));
    }

    /**
     * Soft-deletes a session and its messages (cascade, same transaction
     * semantics as the old physical delete). Returns false when unknown or
     * already deleted.
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteSession(String sessionId) {
        int updated = jdbcTemplate.update(
                "UPDATE chat_session SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", sessionId);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update(
                "UPDATE chat_message SET deleted_at = now() WHERE session_id = ? AND deleted_at IS NULL", sessionId);
        return true;
    }

    /**
     * Truncates the session history from the message at {@code index}
     * (0-based, chronological) inclusive — used by the frontend
     * "edit & resend" flow so the LLM context stays consistent with the UI.
     * Soft-delete (rows kept for audit; queries filter them out).
     *
     * @return number of messages soft-deleted; -1 when the index is out of range
     */
    public int truncateFrom(String sessionId, int index) {
        List<StoredMessage> all = loadMessages(sessionId);
        if (index < 0 || index >= all.size()) {
            return -1;
        }
        java.time.LocalDateTime cutoff = all.get(index).createdAt();
        return jdbcTemplate.update(
                "UPDATE chat_message SET deleted_at = now() WHERE session_id = ? AND created_at >= ? AND deleted_at IS NULL",
                sessionId, cutoff);
    }

    public void saveReflection(String sessionId, String taskSignature, String reflection) {
        jdbcTemplate.update("INSERT INTO agent_reflection (session_id, task_signature, reflection) VALUES (?, ?, ?)",
                sessionId, taskSignature, reflection);
    }

    public void saveStep(String sessionId, int stepIndex, ChatStepDto step) {
        jdbcTemplate.update("""
                INSERT INTO agent_step (session_id, step_index, step_type, title, detail, status, duration_ms,
                                        tool_name, tool_input, tool_result, round_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::json, ?::json, ?)
                """,
                sessionId, stepIndex, step.type(), step.title(), step.detail(), step.status(), step.duration(),
                step.toolName(), toJson(step.input()), toJson(step.result()), step.roundIndex());
    }


    public List<String> loadRecentReflections(String sessionId, String taskSignature, int limit) {
        return jdbcTemplate.query("SELECT reflection FROM agent_reflection WHERE session_id = ? AND task_signature = ? ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> rs.getString("reflection"), sessionId, taskSignature, Math.max(1, Math.min(limit, 10)));
    }

    /** One session row for the sidebar list. */
    public record SessionSummary(
            String id,
            String title,
            /** 标题是否已由 AI 生成；false = 仍是首轮占位标题。 */
            boolean titleGenerated,
            int messageCount,
            java.sql.Timestamp createdAt,
            java.sql.Timestamp lastActivity) {
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

    /**
     * One persisted message row. {@code createdAt} 是落库时间——列是
     * {@code timestamp without time zone}、写入的是本地挂钟时间,必须按
     * {@link java.time.LocalDateTime} 原样读出(不贴时区标签);若用
     * OffsetDateTime 读,JDBC 会按 DB 会话时区(UTC)错误标注 Z,前端会
     * 再加 8 小时。序列化为 ISO {@code yyyy-MM-dd'T'HH:mm[:ss]}。
     */
    public record StoredMessage(
            String role,
            String content,
            List<ChatStepDto> steps,
            List<CitationDto> sources,
            java.time.LocalDateTime createdAt
    ) {

        /** Back-compat constructor for in-memory messages that have no DB timestamp. */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources) {
            this(role, content, steps, sources, null);
        }
    }
}
