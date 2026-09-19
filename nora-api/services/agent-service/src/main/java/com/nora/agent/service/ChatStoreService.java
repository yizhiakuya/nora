package com.nora.agent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

/**
 * 对话会话与消息的持久化({@code schema_agent})。
 * 步骤与来源存为 JSON 字符串。
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
     * 创建会话行;id 已存在时幂等。
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

    /** 保存一条消息及其步骤/来源快照。 */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources) {
        saveMessage(sessionId, role, content, steps, sources, null);
    }

    /**
     * 保存一条消息及其步骤/来源快照与轮次耗时。
     *
     * <p>{@code durationMs} 与 SSE done 事件同源（整轮墙钟耗时）。必须落库：
     * 它此前只随 done 事件下发，刷新/切会话后前端从历史重建消息就丢了，
     * 「查看工作过程 · N 次工具调用 · Xs」的总计时会消失（实测用户反馈）。
     * 非 assistant 消息传 null。
     */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources, Long durationMs) {
        saveMessage(sessionId, role, content, steps, sources, durationMs, null, null);
    }

    /**
     * 完整保存(2026-09-19 上下文计量落库):{@code promptTokens}/{@code contextWindow}
     * 与 done 事件同源——此前只活在 SSE 事件里,刷新后前端上下文指示器回退到
     * 「字符数÷4」估算,中文场景严重低估(实测真实 7.6k 显示成 1k,用户反馈
     * 「上下文不准」)。非 assistant 消息/旧数据传 null。
     */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources, Long durationMs,
                            Integer promptTokens, Long contextWindow) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (id, session_id, role, content, steps, sources, duration_ms, prompt_tokens, context_window) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sessionId, role, content,
                toJson(steps), toJson(sources), durationMs, promptTokens, contextWindow);
    }

    /** 按时间序加载会话的全部消息(排除软删行)。 */
    public List<StoredMessage> loadMessages(String sessionId) {
        List<StoredMessage> loaded = jdbcTemplate.query(
                "SELECT role, content, steps, sources, duration_ms, prompt_tokens, context_window, created_at FROM chat_message "
                        + "WHERE session_id = ? AND deleted_at IS NULL ORDER BY created_at, id",
                (rs, rowNum) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        fromJson(rs.getString("steps"), STEP_LIST),
                        fromJson(rs.getString("sources"), SOURCE_LIST),
                        rs.getObject("duration_ms", Long.class),
                        rs.getObject("prompt_tokens", Integer.class),
                        rs.getObject("context_window", Long.class),
                        rs.getObject("created_at", java.time.LocalDateTime.class)),
                sessionId);
        // steps 按 (id → 状态) 追加式存储(running 先行、终态覆盖),恢复时合并去重,
        // 并丢弃没有终态的悬挂 running(会话中断残留)
        for (int i = 0; i < loaded.size(); i++) {
            List<ChatStepDto> steps = loaded.get(i).steps();
            if (steps == null || steps.isEmpty()) continue;
            // duration_ms IS NULL = 旧写入路径落的库(推理时长是整轮戳记,需要清洗);
            // 新行写入侧已按轮计时,直接保留。
            loaded.set(i, new StoredMessage(loaded.get(i).role(), loaded.get(i).content(),
                    mergeSteps(steps, loaded.get(i).durationMs() == null), loaded.get(i).sources(),
                    loaded.get(i).durationMs(), loaded.get(i).promptTokens(),
                    loaded.get(i).contextWindow(), loaded.get(i).createdAt()));
        }
        return loaded;
    }

    /** 折叠仅追加的步骤行:终态状态优先;悬挂的 running 步骤丢弃。 */
    static List<ChatStepDto> mergeSteps(List<ChatStepDto> steps) {
        return mergeSteps(steps, true);
    }

    /**
     * @param legacy 该消息由旧写入路径落库({@code chat_message.duration_ms IS NULL}):
     *               推理时长是整轮戳记,需要清洗;新行(false)按轮计时直接保留。
     */
    static List<ChatStepDto> mergeSteps(List<ChatStepDto> steps, boolean legacy) {
        java.util.LinkedHashMap<String, ChatStepDto> byId = new java.util.LinkedHashMap<>();
        for (ChatStepDto step : steps) {
            ChatStepDto prev = byId.get(step.id());
            boolean terminal = !"running".equals(step.status()) && !"pending".equals(step.status());
            if (prev == null || terminal) {
                byId.put(step.id(), step);
            }
        }
        List<ChatStepDto> merged = orderByRound(byId.values().stream()
                .filter(s -> !"running".equals(s.status()) && !"pending".equals(s.status()))
                .toList());
        return legacy ? sanitizeReasoningDurations(merged) : merged;
    }

    /**
     * 清掉存量行里被整轮耗时污染的推理计时。
     *
     * <p>背景（2026-09-15，用户实报）：旧写入路径在整轮收尾时把「整轮耗时」盖给该轮
     * 每条推理步骤，于是一个含 4 轮推理的轮次里 4 条「已深度思考」全都显示 17.61s。
     * 旧数据（{@code duration_ms IS NULL}）里推理步骤的 duration 不可信——早期一律是
     * 整轮戳记（单步骤消息同样是错数字），此后到 duration_ms 落库前的一段窗口也无法
     * 逐条甄别，统一置空（前端隐藏秒数——显示一个错数字比不显示更糟）。
     * 仅对存量行调用（见 {@link #mergeSteps(List, boolean)}）；新行写入侧已按轮计时。
     */
    private static List<ChatStepDto> sanitizeReasoningDurations(List<ChatStepDto> steps) {
        boolean any = false;
        for (ChatStepDto step : steps) {
            if (isReasoningStep(step) && step.duration() != null) {
                any = true;
                break;
            }
        }
        if (!any) return steps;
        List<ChatStepDto> out = new java.util.ArrayList<>(steps.size());
        for (ChatStepDto step : steps) {
            out.add(isReasoningStep(step) && step.duration() != null ? withDuration(step, null) : step);
        }
        return out;
    }

    /** 推理步骤：id 前缀 s-reasoning-（区别于 s-error 等同为 think 型的行）。 */
    private static boolean isReasoningStep(ChatStepDto step) {
        return "think".equals(step.type()) && step.id() != null && step.id().startsWith("s-reasoning-");
    }

    private static ChatStepDto withDuration(ChatStepDto step, Long duration) {
        return new ChatStepDto(step.id(), step.type(), step.title(), step.detail(), duration, step.status(),
                step.toolName(), step.input(), step.result(), step.roundIndex(), step.context());
    }

    /**
     * 把步骤排回真实执行顺序：按轮次分组，同一轮里推理（think）排在该轮工具调用之前。
     *
     * <p>背景（2026-09-15 修复）：推理步骤此前只在整轮收尾时统一追加进落库列表，
     * 而工具步骤是实时追加的——于是历史里出现「5 个工具全在前、4 条思考全在后」，
     * 前端拉历史收敛终态后时间线错位（真实顺序是 思考→工具→思考→工具…，与用户在
     * 界面上看到的过程一致）。写入侧已改为首个推理 token 即原位占位；这里对存量行
     * 做等价重排：只把「落在同轮工具之后」的推理步骤挪到该轮首个工具之前，
     * 其余步骤（含缺 roundIndex 的旧行、上下文注入、错误行）相对顺序一概不动。
     *
     * <p>只处理 {@code s-reasoning-*}（isReasoningStep）：同为 think 型的
     * {@code s-compact-N}（整理上下文，roundIndex 是 0 基循环下标，与 1 基的
     * 工具轮号错位）与 {@code s-effort/s-vision} 通知行必须原地不动——曾用宽松的
     * type==think 判定，把压缩步骤错误前移到同轮工具之前（实测复现）。
     */
    private static List<ChatStepDto> orderByRound(List<ChatStepDto> steps) {
        List<ChatStepDto> ordered = new java.util.ArrayList<>(steps);
        for (ChatStepDto step : steps) {
            if (!isReasoningStep(step) || step.roundIndex() == null) continue;
            int from = ordered.indexOf(step);
            if (from < 0) continue;
            int firstTool = -1;
            for (int i = 0; i < ordered.size(); i++) {
                ChatStepDto candidate = ordered.get(i);
                if ("tool".equals(candidate.type()) && step.roundIndex().equals(candidate.roundIndex())) {
                    firstTool = i;
                    break;
                }
            }
            if (firstTool < 0 || from < firstTool) continue; // 该轮没有工具，或推理已在工具之前
            ordered.remove(from);
            ordered.add(firstTool, step);
        }
        return ordered;
    }

    /** 列出存活会话及消息数,最近活跃在前(排除软删)。 */
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
     * 软删会话及其消息(级联,事务语义与原物理删除一致)。
     * 未知或已删除时返回 false。
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
     * 从 {@code index} 处的消息(0 起、按时间序)起含自身截断会话历史——
     * 供前端"编辑并重发"流程使用,让 LLM 上下文与 UI 保持一致。
     * 软删除(行留存审计;查询过滤掉)。
     *
     * @return 软删的消息数;下标越界时为 -1
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

    /** 侧栏列表用的一条会话行。 */
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
            /** 整轮耗时(ms);assistant 消息才有,旧数据/用户消息为 null。 */
            Long durationMs,
            /** 当轮请求的完整 prompt token 估算(done 同源);旧数据为 null。 */
            Integer promptTokens,
            /** 当轮生效的上下文窗口;旧数据为 null。 */
            Long contextWindow,
            java.time.LocalDateTime createdAt
    ) {

        /** 兼容构造:无 DB 时间戳的内存消息。 */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources) {
            this(role, content, steps, sources, null, null, null, null);
        }

        /** 兼容构造:仅耗时(既有测试/调用方)。 */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, java.time.LocalDateTime createdAt) {
            this(role, content, steps, sources, durationMs, null, null, createdAt);
        }
    }
}
