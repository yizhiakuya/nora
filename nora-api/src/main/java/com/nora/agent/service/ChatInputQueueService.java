package com.nora.agent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatRequest;
import com.nora.common.exception.BusinessException;
import com.nora.common.exception.ErrorCategory;

/** 排队记录是服务端的执行凭据，页面只展示记录和保留草稿。 */
@Service
public class ChatInputQueueService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ChatStoreService store;

    public ChatInputQueueService(JdbcTemplate jdbc, ObjectMapper json, ChatStoreService store) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
    }

    public record Input(UUID id, String kind, String content, JsonNode refs, boolean limitToRefs, ChatRequest request) {}
    public record View(List<Input> messages, boolean paused, String latestRunId) {}
    private record Entry(Input input, String state, String runId) {}

    @Transactional
    public View enqueue(String sessionId, Input input) {
        validate(input);
        store.ensureSession(sessionId, ChatStoreService.placeholderTitle(input.request().content()));
        if (jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM chat_session WHERE id=?", Boolean.class, sessionId))
            throw new IllegalArgumentException("会话已删除，不能继续排队");
        var existing = entries(sessionId, input.id(), false);
        // 重试同一入队请求沿用首次冻结的模式，不受后来会话切换影响。
        String mode = input.request().executionMode() != null ? ExecutionMode.parse(input.request().executionMode())
                : existing.isEmpty() ? store.executionMode(sessionId)
                : ExecutionMode.parse(existing.getFirst().input().request().executionMode());
        input = new Input(input.id(), input.kind(), input.content(), input.refs(), input.limitToRefs(), input.request().withExecutionMode(mode));
        if (!existing.isEmpty()) {
            if (!json.valueToTree(existing.getFirst().input()).equals(json.valueToTree(input)))
                throw new IllegalArgumentException("messageId already belongs to different input");
            return view(sessionId);
        }
        jdbc.update("INSERT INTO chat_input_queue(id,session_id,kind,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                input.id(), sessionId, input.kind(), encode(input));
        var saved = entries(sessionId, input.id(), false);
        if (saved.isEmpty() || !json.valueToTree(saved.getFirst().input()).equals(json.valueToTree(input)))
            throw new IllegalArgumentException("messageId already belongs to different input");
        pause(sessionId, false);
        return view(sessionId);
    }

    public View view(String sessionId) {
        var inputs = jdbc.query("SELECT payload FROM chat_input_queue WHERE session_id=? AND state='pending' ORDER BY created_at,id",
                (rs, row) -> decode(rs.getString(1)), sessionId);
        var flags = jdbc.query("SELECT queue_paused FROM chat_session WHERE id=? AND deleted_at IS NULL",
                (rs, row) -> rs.getBoolean(1), sessionId);
        var runs = jdbc.query("SELECT id FROM chat_run WHERE session_id=? ORDER BY started_at DESC,id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), sessionId);
        return new View(inputs, flags.isEmpty() || flags.getFirst(), runs.isEmpty() ? null : runs.getFirst());
    }

    public List<String> readySessions() {
        return jdbc.query("SELECT q.session_id FROM chat_input_queue q JOIN chat_session s ON s.id=q.session_id "
                + "WHERE q.state='pending' AND NOT s.queue_paused AND s.deleted_at IS NULL "
                + "GROUP BY q.session_id ORDER BY min(q.created_at) LIMIT 32", (rs, row) -> rs.getString(1));
    }

    @Transactional
    public Input claim(String sessionId, String runId) {
        var rows = jdbc.query("SELECT q.payload FROM chat_input_queue q JOIN chat_session s ON s.id=q.session_id "
                + "WHERE q.session_id=? AND q.state='pending' AND NOT s.queue_paused AND s.deleted_at IS NULL "
                + "ORDER BY q.created_at,q.id LIMIT 1 FOR UPDATE OF q", (rs, row) -> decode(rs.getString(1)), sessionId);
        if (rows.isEmpty()) return null;
        Input input = rows.getFirst();
        String actualRun = "compact".equals(input.kind()) ? "compact-" + input.id() : runId;
        jdbc.update("UPDATE chat_input_queue SET state='claimed',run_id=? WHERE id=?", actualRun, input.id());
        return input;
    }

    public int releaseUnstarted(UUID id) {
        return jdbc.update("UPDATE chat_input_queue q SET state='pending',run_id=NULL WHERE id=? AND state='claimed' "
                + "AND NOT EXISTS(SELECT 1 FROM chat_run r WHERE r.id=q.run_id)", id);
    }

    @jakarta.annotation.PostConstruct
    void recoverClaims() {
        jdbc.update("UPDATE chat_input_queue q SET state=CASE WHEN EXISTS(SELECT 1 FROM chat_run r WHERE r.id=q.run_id) "
                + "THEN 'consumed' ELSE 'pending' END WHERE state='claimed'");
    }

    public void pause(String sessionId, boolean paused) {
        jdbc.update("UPDATE chat_session SET queue_paused=? WHERE id=? AND deleted_at IS NULL", paused, sessionId);
    }

    @Transactional
    public Input withdraw(String sessionId, UUID id) {
        Entry entry = requireEntry(sessionId, id, true);
        if (!List.of("pending", "withdrawn").contains(entry.state())) throw conflict();
        jdbc.update("UPDATE chat_input_queue SET state='withdrawn' WHERE id=?", id);
        return entry.input();
    }

    public Input get(String sessionId, UUID id) { return requireEntry(sessionId, id, false).input(); }

    @Transactional
    public void consumeForInsert(String sessionId, UUID id, String turnId) {
        Entry entry = requireEntry(sessionId, id, true);
        String steeringId = "steer-" + turnId + "-" + id;
        ChatRequest request = entry.input().request();
        if ("consumed".equals(entry.state()) && turnId.equals(entry.runId())
                && store.hasSteeringMessage(sessionId, steeringId, request.content(), request.context())) return;
        if (!"pending".equals(entry.state()) || !"message".equals(entry.input().kind())) throw conflict();
        store.saveSteeringMessage(sessionId, steeringId, request.content(), request.context());
        jdbc.update("UPDATE chat_input_queue SET state='consumed',run_id=? WHERE id=?", turnId, id);
    }

    private Entry requireEntry(String sessionId, UUID id, boolean lock) {
        var rows = entries(sessionId, id, lock);
        if (rows.isEmpty()) throw new IllegalArgumentException("queued message not found");
        return rows.getFirst();
    }

    private List<Entry> entries(String sessionId, UUID id, boolean lock) {
        return jdbc.query("SELECT payload,state,run_id FROM chat_input_queue WHERE session_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Entry(decode(rs.getString(1)), rs.getString(2), rs.getString(3)), sessionId, id);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCategory.CONFLICT, "QUEUE_ALREADY_CONSUMED", "消息已被领取或插入，不能再撤回或重复发送", null);
    }

    private static void validate(Input input) {
        if (input == null || input.id() == null || input.kind() == null || !List.of("message", "compact").contains(input.kind())) throw new IllegalArgumentException("invalid queued input");
        ChatRequest r = input.request();
        if (r == null || r.content() == null || r.content().isBlank() || r.content().length() > 8000
                || input.content() == null || input.content().length() > 8000 || input.refs() == null
                || !input.refs().isArray() || input.refs().size() > 30 || input.refs().toString().length() > 32_000)
            throw new IllegalArgumentException("invalid queued content or references");
        if (r.sender() != null && !"user".equals(r.sender()) || Boolean.TRUE.equals(r.unattended()))
            throw new IllegalArgumentException("queued input must be a user request");
        if ("compact".equals(input.kind()) && (!(r.content().equals("/compact") || r.content().startsWith("/compact "))
                || r.content().substring("/compact".length()).trim().length() > 4000))
            throw new IllegalArgumentException("invalid compact command");
    }

    private String encode(Input input) {
        try { return json.writeValueAsString(input); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("invalid queue payload", e); }
    }
    private Input decode(String text) {
        try {
            Input input = json.readValue(text, Input.class);
            return new Input(input.id(), input.kind(), input.content(), input.refs(), input.limitToRefs(),
                    input.request().withExecutionMode(ExecutionMode.parse(input.request().executionMode())));
        }
        catch (JsonProcessingException e) { throw new IllegalStateException("invalid stored queue payload", e); }
    }
}
