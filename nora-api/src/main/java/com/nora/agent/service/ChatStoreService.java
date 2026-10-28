package com.nora.agent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.ContextCheckpointDto;
import com.nora.agent.dto.ContextUsageDto;
import com.nora.agent.dto.TaskContext;

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

    private final TerminalService terminalService;

    public ChatStoreService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, TerminalService terminalService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.terminalService = terminalService;
    }


    private ChatStepDto currentExecution(ChatStepDto step, java.util.Map<String, com.nora.agent.dto.CommandExecutionDto> executions) {
        var result = step.result();
        if (result == null || result.execution() == null) return step;
        var execution = executions.get(result.execution().executionId());
        if (execution == null) return step;
        String content = BuiltinTools.commandContent(execution);
        var updated = new ChatStepDto.StepResult(content, execution.running() ? "后台运行" : execution.status(),
                result.rowCount(), result.lineCount(), result.truncated(), content.startsWith("ERROR:") ? content : null,
                result.files(), result.focusTarget(), result.fileErrors(), result.fileChanges(), execution);
        return new ChatStepDto(step.id(), step.type(), step.title(), updated.summary(), step.duration(), step.status(),
                step.toolName(), step.input(), updated, step.roundIndex(), step.context(), step.progress(), step.parentStepId(), step.callIndex());
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

    /** 项目绑定和排队可以先建会话；首次命名以消息是否存在为准。 */
    public boolean initializeTitleForFirstMessage(String sessionId, String content) {
        return jdbcTemplate.update("""
                UPDATE chat_session s SET title = ?
                WHERE s.id = ? AND s.deleted_at IS NULL AND NOT s.title_generated
                  AND coalesce(s.origin, 'user') <> 'automation'
                  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = s.id)
                """, placeholderTitle(content), sessionId) == 1;
    }

    /**
     * 确保定时任务会话存在(2026-09-20,定时任务=往会话发消息)。
     *
     * <p>会话 id 由 自动任务模块 按规则确定性生成({@code auto-rule-<id>}),
     * 标题「定时任务:规则名」。被用户删除过(deleted_at 非空)时**复活**——
     * 规则仍会触发,不能把消息写进已删会话;标题以传入值为准(规则可改名)。
     * origin 标记为 automation(会话列表可区分)。
     */
    public void ensureAutomationSession(String sessionId, String title) {
        jdbcTemplate.update("""
                INSERT INTO chat_session (id, title, origin) VALUES (?, ?, 'automation')
                ON CONFLICT (id) DO UPDATE SET
                    deleted_at = NULL, title = EXCLUDED.title, origin = 'automation'
                WHERE chat_session.origin = 'automation' OR chat_session.origin IS NULL
                """, sessionId, clampTitle(title));
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
        saveMessage(sessionId, role, content, steps, sources, durationMs, promptTokens, contextWindow,
                "user".equals(role) ? "user" : "assistant");
    }

    /**
     * 带发送者标记的保存(2026-09-20,定时任务=往会话发消息):
     * {@code sender} 为 user/automation/assistant——定时任务触发的用户侧消息
     * 标 automation,前端气泡显示「定时任务」徽章。
     */
    public void saveMessage(String sessionId, String role, String content,
                            List<ChatStepDto> steps, List<CitationDto> sources, Long durationMs,
                            Integer promptTokens, Long contextWindow, String sender) {
        saveMessageAt(sessionId, role, content, steps, sources, durationMs, promptTokens, contextWindow, sender, null, null, null);
    }

    private void saveMessageAt(String sessionId, String role, String content,
                               List<ChatStepDto> steps, List<CitationDto> sources, Long durationMs,
                                Integer promptTokens, Long contextWindow, String sender, java.time.LocalDateTime createdAt,
                                String runId, TaskContext taskContext) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (id, session_id, role, content, steps, sources, duration_ms, prompt_tokens, context_window, sender, created_at, run_id, task_context) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, LOCALTIMESTAMP), ?, ?::jsonb)",
                "compaction".equals(sender) && runId != null ? runId : UUID.randomUUID().toString(), sessionId, role, content,
                toJson(steps), toJson(sources), durationMs, promptTokens, contextWindow,
                sender == null ? "user" : sender, createdAt, runId, toJson(taskContext));
    }

    /** 插入消息把同一次运行分成聊天段；已开始的工具更新仍留在其原段中。 */
    @Transactional
    public void saveAssistantTurn(String runId, String sessionId, String content, List<ChatStepDto> steps,
                                  List<CitationDto> sources, Long durationMs, Integer promptTokens, Long contextWindow,
                                  TaskContext taskContext) {
        int from = 0;
        long elapsed = 0;
        for (int index = 0; index < steps.size(); index++) {
            ChatStepDto boundary = steps.get(index);
            if (boundary.context() == null || !"user-input".equals(boundary.context().kind())) continue;
            var createdAt = jdbcTemplate.queryForObject(
                    "SELECT created_at FROM chat_message WHERE session_id = ? AND id = ? AND sender = 'steering'",
                    (rs, row) -> rs.getObject(1, java.time.LocalDateTime.class), sessionId, boundary.id());
            long at = boundary.duration() == null ? elapsed : boundary.duration();
            if (index > from) {
                var segment = List.copyOf(steps.subList(from, index));
                String answer = segment.stream().filter(step -> "answer".equals(step.type()))
                        .map(ChatStepDto::detail).filter(java.util.Objects::nonNull).reduce((first, last) -> last).orElse("");
                // 前段按插入时刻排序，避免收尾落库把已有 AI 内容排到用户消息后面。
                saveMessageAt(sessionId, "assistant", answer, segment, null, Math.max(0, at - elapsed),
                        null, null, "assistant", createdAt.minusNanos(1000), runId, taskContext);
            }
            from = index + 1;
            elapsed = at;
        }
        saveMessageAt(sessionId, "assistant", content, List.copyOf(steps.subList(from, steps.size())), sources,
                durationMs == null ? null : Math.max(0, durationMs - elapsed), promptTokens, contextWindow,
                "assistant", null, runId, taskContext);
    }

    @Transactional
    public void completeRun(String runId, String sessionId, String content, List<ChatStepDto> steps,
                            List<CitationDto> sources, Long durationMs, Integer promptTokens, Long contextWindow,
                            TaskContext taskContext, String status, String stopReason, String errorMessage,
                            boolean hadToolErrors) {
        completeRun(runId, sessionId, content, steps, sources, durationMs, promptTokens, contextWindow, taskContext,
                status, stopReason, errorMessage, hadToolErrors, null, false);
    }

    @Transactional
    public void completeRun(String runId, String sessionId, String content, List<ChatStepDto> steps,
                            List<CitationDto> sources, Long durationMs, Integer promptTokens, Long contextWindow,
                            TaskContext taskContext, String status, String stopReason, String errorMessage,
                            boolean hadToolErrors, ContextCheckpointDto checkpoint, boolean compaction) {
        var states = jdbcTemplate.query("SELECT status FROM chat_run WHERE id=? AND session_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId, sessionId);
        if (states.isEmpty()) throw new IllegalStateException("run not found: " + runId);
        if (java.util.Set.of("completed", "cancelled", "failed", "partial", "interrupted").contains(states.get(0))) return;
        if (compaction) saveMessageAt(sessionId, "assistant", "", steps, null, durationMs,
                promptTokens, contextWindow, "compaction", null, runId, null);
        else saveAssistantTurn(runId, sessionId, content, steps, sources, durationMs, promptTokens, contextWindow, taskContext);
        if (checkpoint != null) {
            saveCheckpoint(sessionId, checkpoint);
            for (var step : steps) if ("s-compaction".equals(step.id())) saveStep(sessionId, runId, steps.size(), step);
            updateRunContext(runId, checkpoint.contextUsage());
        }
        finishRun(runId, status, stopReason, errorMessage, hadToolErrors);
    }

    public ContextCheckpointDto loadCheckpoint(String sessionId) {
        var checkpoints = jdbcTemplate.query("SELECT c.checkpoint FROM chat_context_checkpoint c "
                + "JOIN chat_session s ON s.id = c.session_id AND s.deleted_at IS NULL "
                + "WHERE c.session_id = ? AND EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = c.session_id "
                + "AND m.id = c.checkpoint->>'boundaryId' AND m.deleted_at IS NULL)",
                (rs, row) -> readJson(rs.getString(1), ContextCheckpointDto.class), sessionId);
        return checkpoints.isEmpty() ? null : checkpoints.getFirst();
    }

    public void saveCheckpoint(String sessionId, ContextCheckpointDto checkpoint) {
        int written = jdbcTemplate.update("INSERT INTO chat_context_checkpoint (session_id, checkpoint) "
                + "SELECT ?, ?::jsonb WHERE EXISTS (SELECT 1 FROM chat_message m JOIN chat_session s ON s.id = m.session_id "
                + "WHERE m.id = ? AND m.session_id = ? AND m.deleted_at IS NULL AND s.deleted_at IS NULL) "
                + "ON CONFLICT (session_id) DO UPDATE SET checkpoint = EXCLUDED.checkpoint",
                sessionId, toJson(checkpoint), checkpoint.boundaryId(), sessionId);
        if (written != 1) throw new IllegalStateException("会话历史已更新，压缩检查点未发布");
    }

    public ContextView contextView(String sessionId) {
        String usageSql = "SELECT r.context_usage FROM chat_run r JOIN chat_session s ON s.id = r.session_id "
                + "WHERE r.session_id = ? AND s.deleted_at IS NULL AND r.context_usage IS NOT NULL "
                + "AND (r.finished_at IS NULL OR EXISTS (SELECT 1 FROM chat_message m WHERE m.run_id = r.id AND m.deleted_at IS NULL)) ";
        var usages = jdbcTemplate.query(usageSql + "ORDER BY r.started_at DESC LIMIT 1",
                (rs, row) -> readJson(rs.getString(1), ContextUsageDto.class), sessionId);
        var requests = jdbcTemplate.query(usageSql + "AND r.context_usage->>'source' = 'upstream' ORDER BY r.started_at DESC LIMIT 1",
                (rs, row) -> readJson(rs.getString(1), ContextUsageDto.class), sessionId);
        return new ContextView(loadCheckpoint(sessionId), usages.isEmpty() ? null : usages.getFirst(),
                requests.isEmpty() ? null : requests.getFirst());
    }

    public record ContextView(ContextCheckpointDto checkpoint, ContextUsageDto latestUsage, ContextUsageDto latestRequestUsage) {}

    public CompactRun findCompactRun(String runId) {
        var runs = jdbcTemplate.query("SELECT session_id, content, status, error_message FROM chat_run WHERE id = ? AND kind = 'compact'",
                (rs, row) -> new CompactRun(rs.getString("session_id"), rs.getString("content"), rs.getString("status"),
                        rs.getString("error_message")), runId);
        return runs.isEmpty() ? null : runs.getFirst();
    }

    public record CompactRun(String sessionId, String content, String status, String errorMessage) {}

    @Transactional
    public void startCompactRun(String runId, String sessionId, String content) {
        jdbcTemplate.update("INSERT INTO chat_run (id, session_id, status, content, kind) VALUES (?, ?, 'running', ?, 'compact')",
                runId, sessionId, content);
        consumeQueuedRun(runId);
    }

    /** 稳定消息 ID 让补充指令的 HTTP 重试不产生第二条用户消息。 */
    public boolean hasSteeringMessage(String sessionId, String id, String content, TaskContext context) {
        var saved = jdbcTemplate.query(
                "SELECT content, task_context FROM chat_message WHERE session_id = ? AND id = ? AND sender = 'steering' AND deleted_at IS NULL",
                (rs, rowNum) -> new TurnStreamRegistry.UserInput(id, rs.getString("content"), readJson(rs.getString("task_context"), TaskContext.class)), sessionId, id);
        if (saved.isEmpty()) return false;
        if (!new TurnStreamRegistry.UserInput(id, content, context).equals(saved.getFirst())) throw new IllegalArgumentException("messageId already has different input");
        return true;
    }

    public void saveSteeringMessage(String sessionId, String id, String content, TaskContext context) {
        jdbcTemplate.update("INSERT INTO chat_message (id, session_id, role, content, sender, task_context) "
                + "VALUES (?, ?, 'user', ?, 'steering', ?::jsonb) ON CONFLICT (id) DO NOTHING", id, sessionId, content, toJson(context));
        if (!hasSteeringMessage(sessionId, id, content, context)) throw new IllegalArgumentException("messageId already exists");
    }

    /** 按时间序加载会话的全部消息(排除软删行)。 */
    public List<StoredMessage> loadMessages(String sessionId) {
        List<StoredMessage> loaded = jdbcTemplate.query(
                "SELECT m.id, m.role, CASE WHEN m.sender = 'compaction' THEN r.content ELSE m.content END AS content, "
                        + "m.steps, m.sources, m.duration_ms, m.prompt_tokens, m.context_window, "
                        + "m.created_at, m.sender, m.task_context, m.run_id, CASE WHEN m.role = 'assistant' AND "
                        + "row_number() OVER (PARTITION BY m.run_id, m.role ORDER BY m.created_at DESC, m.id DESC) = 1 "
                        + "THEN r.status END AS run_status, CASE WHEN m.role = 'assistant' AND "
                        + "row_number() OVER (PARTITION BY m.run_id, m.role ORDER BY m.created_at DESC, m.id DESC) = 1 "
                        + "THEN r.context_usage END AS context_usage FROM chat_message m "
                        + "LEFT JOIN chat_run r ON r.id = m.run_id "
                        + "WHERE m.session_id = ? AND m.deleted_at IS NULL ORDER BY m.created_at, m.id",
                (rs, rowNum) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        fromJson(rs.getString("steps"), STEP_LIST),
                        fromJson(rs.getString("sources"), SOURCE_LIST),
                        rs.getObject("duration_ms", Long.class),
                        rs.getObject("prompt_tokens", Integer.class),
                        rs.getObject("context_window", Long.class),
                        rs.getObject("created_at", java.time.LocalDateTime.class),
                        rs.getString("sender"), rs.getString("id"), rs.getString("run_status"),
                        readJson(rs.getString("task_context"), TaskContext.class),
                        readJson(rs.getString("context_usage"), ContextUsageDto.class), rs.getString("run_id")),
                sessionId);
        var ids = loaded.stream().filter(message -> message.steps() != null).flatMap(message -> message.steps().stream())
                .filter(step -> step.result() != null && step.result().execution() != null)
                .map(step -> step.result().execution().executionId()).collect(java.util.stream.Collectors.toSet());
        var executions = terminalService.snapshots(sessionId, ids);
        // steps 按 (id → 状态) 追加式存储(running 先行、终态覆盖),恢复时合并去重,
        // 并丢弃没有终态的悬挂 running(会话中断残留)
        for (int i = 0; i < loaded.size(); i++) {
            List<ChatStepDto> steps = loaded.get(i).steps();
            if (steps != null) steps = steps.stream().map(step -> currentExecution(step, executions)).toList();
            if (steps == null || steps.isEmpty()) continue;
            // duration_ms IS NULL = 旧写入路径落的库(推理时长是整轮戳记,需要清洗);
            // 新行写入侧已按轮计时,直接保留。
            loaded.set(i, new StoredMessage(loaded.get(i).role(), loaded.get(i).content(),
                    mergeSteps(steps, loaded.get(i).durationMs() == null), loaded.get(i).sources(),
                    loaded.get(i).durationMs(), loaded.get(i).promptTokens(),
                    loaded.get(i).contextWindow(), loaded.get(i).createdAt(), loaded.get(i).sender(), loaded.get(i).id(),
                    loaded.get(i).runStatus(), loaded.get(i).taskContext(), loaded.get(i).contextUsage(), loaded.get(i).runId()));
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
        return querySessions("", null, null, 0);
    }

    public SessionSummary session(String id) {
        return querySessions("", id, 1, 0).stream().findFirst()
                .orElseThrow(() -> new com.nora.common.exception.BusinessException(404, "session not found: " + id));
    }

    public String executionMode(String sessionId) {
        var modes = jdbcTemplate.query("SELECT execution_mode FROM chat_session WHERE id=? AND deleted_at IS NULL",
                (rs, row) -> rs.getString(1), sessionId);
        return modes.isEmpty() ? "standard" : modes.getFirst();
    }

    @Transactional
    public void setExecutionMode(String sessionId, String mode) {
        mode = ExecutionMode.parse(mode);
        ensureSession(sessionId, "新对话");
        if (jdbcTemplate.update("UPDATE chat_session SET execution_mode=? WHERE id=? AND deleted_at IS NULL", mode, sessionId) != 1)
            throw new IllegalArgumentException("会话已删除，不能切换执行模式");
    }

    public SessionPage searchSessions(String query, int offset, int limit) {
        if (query.length() > 200 || offset < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("query must be <= 200 characters; offset >= 0; limit between 1 and 100");
        var items = querySessions(query.trim(), null, limit + 1, offset);
        return new SessionPage(items.stream().limit(limit).toList(), items.size() > limit);
    }

    public record SessionPage(List<SessionSummary> items, boolean hasMore) {}

    private List<SessionSummary> querySessions(String query, String id, Integer limit, int offset) {
        var args = new java.util.ArrayList<Object>();
        String filter = "";
        if (id != null) {
            filter = " AND s.id = ?";
            args.add(id);
        } else if (!query.isEmpty()) {
            filter = """
                     AND (strpos(lower(s.title), lower(?)) > 0 OR strpos(lower(s.id), lower(?)) > 0
                          OR EXISTS (SELECT 1 FROM chat_message hit WHERE hit.session_id = s.id
                              AND hit.deleted_at IS NULL AND strpos(lower(hit.content), lower(?)) > 0))
                    """;
            args.addAll(List.of(query, query, query));
        }
        if (limit != null) args.addAll(List.of(limit, offset));
        return jdbcTemplate.query(
                """
                SELECT s.id, s.title, s.title_generated, s.created_at, s.origin, count(m.id) AS message_count,
                       max(m.created_at) AS last_activity
                FROM chat_session s
                LEFT JOIN chat_message m ON m.session_id = s.id AND m.deleted_at IS NULL
                WHERE s.deleted_at IS NULL
                """ + filter + """
                 GROUP BY s.id, s.title, s.title_generated, s.created_at, s.origin
                 ORDER BY max(m.created_at) DESC NULLS LAST, s.created_at DESC, s.id DESC
                """ + (limit == null ? "" : " LIMIT ? OFFSET ?"),
                (rs, rowNum) -> new SessionSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getBoolean("title_generated"),
                        rs.getInt("message_count"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("last_activity"),
                        rs.getString("origin")), args.toArray());
    }

    /**
     * 软删会话及其消息(级联,事务语义与原物理删除一致)。
     * 未知或已删除时返回 false。
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteSession(String sessionId) {
        jdbcTemplate.update("DELETE FROM chat_context_checkpoint WHERE session_id = ?", sessionId);
        int updated = jdbcTemplate.update(
                "UPDATE chat_session SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", sessionId);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update(
                "UPDATE chat_message SET deleted_at = now() WHERE session_id = ? AND deleted_at IS NULL", sessionId);
        // 会话删除时把遗留的非终态运行收尾(2026-09-21):否则 running 行永远悬着
        jdbcTemplate.update(
                "UPDATE chat_run SET status = 'cancelled', finished_at = now(), updated_at = now() "
                        + "WHERE session_id = ? AND status IN ('queued','running','awaiting_approval','cancelling')",
                sessionId);
        return true;
    }

    /**
     * 从 {@code index} 处的消息(0 起、按时间序)起含自身截断会话历史——
     * 供前端"编辑并重发"流程使用,让 LLM 上下文与 UI 保持一致。
     * 软删除(行留存审计;查询过滤掉)。
     *
     * @return 软删的消息数;下标越界时为 -1
     */
    @Transactional
    public int truncateFrom(String sessionId, int index) {
        List<StoredMessage> all = loadMessages(sessionId);
        if (index < 0 || index >= all.size()) {
            return -1;
        }
        java.time.LocalDateTime cutoff = all.get(index).createdAt();
        jdbcTemplate.update("DELETE FROM chat_context_checkpoint WHERE session_id = ?", sessionId);
        return jdbcTemplate.update(
                "UPDATE chat_message SET deleted_at = now() WHERE session_id = ? AND created_at >= ? AND deleted_at IS NULL",
                sessionId, cutoff);
    }

    // ---------- 对话运行生命周期(M3-01,2026-09-20,方案 §6.4) ----------

    /**
     * 轮次开始:落一行 running(稳定 runId,与 SSE/MDC 的 turnId 同源)。
     * 初始消息与运行一起提交，避免重启后只有运行而没有用户要求。
     */
    @Transactional
    public void startRun(String runId, String sessionId, String content, String sender, TaskContext taskContext) {
        startRun(runId, sessionId, content, sender, taskContext, "standard");
    }

    @Transactional
    public void startRun(String runId, String sessionId, String content, String sender, TaskContext taskContext, String executionMode) {
        jdbcTemplate.update("""
                INSERT INTO chat_run (id, session_id, status, content, execution_mode) VALUES (?, ?, 'running', ?, ?)
                """, runId, sessionId, content, ExecutionMode.parse(executionMode));
        saveMessageAt(sessionId, "user", content, null, null, null, null, null, sender, null, runId, taskContext);
        consumeQueuedRun(runId);
    }

    private void consumeQueuedRun(String runId) {
        jdbcTemplate.update("UPDATE chat_input_queue SET state='consumed' WHERE run_id=? AND state='claimed'", runId);
    }

    /** 运行状态推进(awaiting_approval / cancelling 等中间态)。 */
    public void updateRunStatus(String runId, String status) {
        jdbcTemplate.update(
                "UPDATE chat_run SET status = ?, updated_at = now() WHERE id = ? "
                        + "AND status IN ('queued', 'running', 'awaiting_approval')", status, runId);
    }

    /** 会话内全部非终态运行 → cancelling(M3-02:取消请求已受理、工作未退出)。 */
    public void markSessionRunsCancelling(String sessionId) {
        jdbcTemplate.update("""
                UPDATE chat_run SET status = 'cancelling', updated_at = now()
                WHERE session_id = ? AND status IN ('queued', 'running', 'awaiting_approval')
                """, sessionId);
    }

    /** 轮次结束:更新**同一行**为终态(方案约束:不插入第二条重复行)。 */
    public void finishRun(String runId, String status) {
        finishRun(runId, status, status, null, false);
    }

    public void finishRun(String runId, String status, String stopReason, String errorMessage, boolean hadToolErrors) {
        jdbcTemplate.update("""
                UPDATE chat_run SET status = ?, stop_reason = ?, error_message = ?, had_tool_errors = ?,
                                    finished_at = now(), updated_at = now() WHERE id = ?
                """, status, stopReason, errorMessage, hadToolErrors, runId);
        if (!"completed".equals(status)) jdbcTemplate.update("UPDATE chat_session SET queue_paused=TRUE "
                + "WHERE id=(SELECT session_id FROM chat_run WHERE id=?)", runId);
    }

    /**
     * 启动时恢复已落库的过程；只恢复记录，不重放工具。
     *
     * @return 已恢复的会话，供启动流程清理旧审批票据
     */
    @Transactional
    public List<String> recoverStaleRuns(java.util.Set<String> pendingFinalizations) {
        // shortcut: 单实例恢复；多实例部署时按进程归属筛选遗留运行。
        var stale = jdbcTemplate.query("""
                SELECT r.id, r.session_id, r.status, r.kind FROM chat_run r
                JOIN chat_session s ON s.id = r.session_id AND s.deleted_at IS NULL
                WHERE r.status IN ('queued', 'running', 'awaiting_approval', 'cancelling')
                ORDER BY r.started_at, r.id FOR UPDATE OF r
                """, (rs, row) -> new RestartedRun(rs.getString("id"), rs.getString("session_id"), rs.getString("status"), rs.getString("kind")))
                .stream().filter(run -> !pendingFinalizations.contains(run.id())).toList();
        for (var run : stale) {
            var steps = mergeSteps(loadRunSteps(run.id()).stream().map(ChatStoreService::interruptedStep).toList(), false);
            boolean cancelled = "cancelling".equals(run.status());
            // 收尾消息提交后、运行终态写入前也可能退出；已有消息不能再生成一份。
            boolean saved = jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message "
                    + "WHERE run_id = ? AND role = 'assistant' AND deleted_at IS NULL", Integer.class, run.id()) > 0;
            if (!saved) {
                if ("compact".equals(run.kind())) {
                    saveMessageAt(run.sessionId(), "assistant", "", List.of(new ChatStepDto("s-error", "context", "压缩已中断",
                            "服务重启中断了压缩，原上下文保留。", null, "partial", null, null, null, null)), null,
                            null, null, null, "compaction", null, run.id(), null);
                    finishRun(run.id(), cancelled ? "cancelled" : "interrupted", "process_restarted", null, false);
                    continue;
                }
                var contexts = jdbcTemplate.query("SELECT task_context FROM chat_message "
                        + "WHERE run_id = ? AND role = 'user' AND deleted_at IS NULL ORDER BY created_at, id LIMIT 1",
                        (rs, row) -> readJson(rs.getString("task_context"), TaskContext.class), run.id());
                String notice = cancelled ? "用户已要求停止，本轮因服务重启结束，已保留已保存的工作过程。"
                        : "本轮因服务重启中断。已完成的工具结果已保留；未执行的工具与结果未知的操作分别标记。"
                                + "继续时复用已完成结果，先核实结果未知的操作，不要将其当作失败自动重放。";
                String answer = steps.stream().filter(s -> "answer".equals(s.type()))
                        .map(ChatStepDto::detail).filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse("");
                saveAssistantTurn(run.id(), run.sessionId(), notice + (answer.isBlank() ? "" : "\n\n" + answer),
                        steps, null, null, null, null, contexts.isEmpty() ? null : contexts.getFirst());
            }
            boolean hadToolErrors = steps.stream().anyMatch(s -> s.toolName() != null
                    && ("unknown".equals(s.status()) || "failed".equals(s.status()) || "partial".equals(s.status())));
            finishRun(run.id(), cancelled ? "cancelled" : "interrupted", "process_restarted", null, hadToolErrors);
        }
        return stale.stream().map(RestartedRun::sessionId).distinct().toList();
    }

    private record RestartedRun(String id, String sessionId, String status, String kind) {}

    private List<ChatStepDto> loadRunSteps(String runId) {
        return jdbcTemplate.query("""
                SELECT step_id, step_type, title, detail, duration_ms, status, tool_name,
                       tool_input, tool_result, round_index, context, parent_step_id, call_index
                FROM agent_step WHERE run_id = ? AND step_id IS NOT NULL ORDER BY id
                """, (rs, row) -> new ChatStepDto(rs.getString("step_id"), rs.getString("step_type"),
                rs.getString("title"), rs.getString("detail"), rs.getObject("duration_ms", Long.class),
                rs.getString("status"), rs.getString("tool_name"),
                readJson(rs.getString("tool_input"), ChatStepDto.StepInput.class),
                readJson(rs.getString("tool_result"), ChatStepDto.StepResult.class),
                rs.getObject("round_index", Integer.class), readJson(rs.getString("context"), ChatStepDto.ContextInfo.class))
                .childOf(rs.getString("parent_step_id"), rs.getObject("call_index", Integer.class)), runId);
    }

    static ChatStepDto interruptedStep(ChatStepDto step) {
        boolean pending = "pending".equals(step.status());
        if (!pending && !"running".equals(step.status())) return step;
        if (step.toolName() == null) {
            return new ChatStepDto(step.id(), step.type(), step.title(), step.detail(), step.duration(), "partial",
                    null, step.input(), step.result(), step.roundIndex(), step.context());
        }
        String message = pending ? "ERROR: 未执行：服务在工具开始执行前重启，原审批或提问已失效。"
                : "ERROR: 结果未知：调用已开始，但服务重启前未保存终态。先核实实际状态，无法核实则向用户确认，不要自动重放。";
        return new ChatStepDto(step.id(), step.type(), step.title(), step.detail(), step.duration(),
                pending ? "declined" : "unknown", step.toolName(), step.input(),
                new ChatStepDto.StepResult(message, pending ? "未执行" : "结果未知", null, null, false, message),
                step.roundIndex(), step.context()).childOf(step.parentStepId(), step.callIndex());
    }

    public void updateRunContext(String runId, ContextUsageDto usage) {
        jdbcTemplate.update("UPDATE chat_run SET context_usage = ?::jsonb WHERE id = ? AND finished_at IS NULL",
                toJson(usage), runId);
    }

    /** 最近运行(任务页「正在处理」聚合;按开始时间倒序)。 */
    public List<RunView> listRuns(List<String> statuses, int limit) {
        String placeholders = statuses == null || statuses.isEmpty()
                ? null : String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = "SELECT r.id, r.session_id, s.title, r.status, r.content, r.started_at, r.finished_at, "
                + "r.stop_reason, r.error_message, r.had_tool_errors "
                + "FROM chat_run r JOIN chat_session s ON s.id = r.session_id AND s.deleted_at IS NULL "
                + (placeholders == null ? "" : "WHERE r.status IN (" + placeholders + ") ")
                + "ORDER BY r.started_at DESC LIMIT ?";
        Object[] args = placeholders == null
                ? new Object[]{limit}
                : java.util.stream.Stream.concat(statuses.stream(), java.util.stream.Stream.of(limit)).toArray();
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RunView(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("content"),
                rs.getObject("started_at", java.time.LocalDateTime.class),
                rs.getObject("finished_at", java.time.LocalDateTime.class),
                rs.getString("stop_reason"), rs.getString("error_message"), rs.getBoolean("had_tool_errors")), args);
    }

    /** 任务页运行条目(对话来源)。 */
    public record RunView(
            String id,
            String sessionId,
            String sessionTitle,
            String status,
            String content,
            java.time.LocalDateTime startedAt,
            java.time.LocalDateTime finishedAt,
            String stopReason,
            String errorMessage,
            boolean hadToolErrors) {
    }

    public void saveStep(String sessionId, int stepIndex, ChatStepDto step) {
        saveStep(sessionId, null, stepIndex, step);
    }

    public void saveStep(String sessionId, String runId, int stepIndex, ChatStepDto step) {
        jdbcTemplate.update("""
                INSERT INTO agent_step (session_id, step_index, step_type, title, detail, status, duration_ms,
                                        tool_name, tool_input, tool_result, round_index, run_id, step_id, context, parent_step_id, call_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::json, ?::json, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                sessionId, stepIndex, step.type(), step.title(), step.detail(), step.status(), step.duration(),
                step.toolName(), toJson(step.input()), toJson(step.result()), step.roundIndex(), runId, step.id(), toJson(step.context()),
                step.parentStepId(), step.callIndex());
    }


    /** 侧栏列表用的一条会话行。 */
    public record SessionSummary(
            String id,
            String title,
            /** 标题是否已由 AI 生成；false = 仍是首轮占位标题。 */
            boolean titleGenerated,
            int messageCount,
            java.sql.Timestamp createdAt,
            java.sql.Timestamp lastActivity,
            /** user=用户创建;automation=定时任务会话(前端列表区分显示)。 */
            String origin) {
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist chat data", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid saved " + type.getSimpleName(), e);
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
            java.time.LocalDateTime createdAt,
            /** user=用户发送;automation=定时任务发送;assistant=AI;旧数据 null 按 user 处理。 */
            String sender,
            String id,
            String runStatus,
            TaskContext taskContext,
            ContextUsageDto contextUsage,
            String runId
    ) {

        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, Integer promptTokens, Long contextWindow,
                             java.time.LocalDateTime createdAt, String sender, String id, String runStatus, TaskContext taskContext) {
            this(role, content, steps, sources, durationMs, promptTokens, contextWindow, createdAt, sender, id, runStatus, taskContext, null, null);
        }

        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, Integer promptTokens, Long contextWindow,
                             java.time.LocalDateTime createdAt, String sender, String id) {
            this(role, content, steps, sources, durationMs, promptTokens, contextWindow, createdAt, sender, id, null, null);
        }

        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, Integer promptTokens, Long contextWindow,
                             java.time.LocalDateTime createdAt, String sender) {
            this(role, content, steps, sources, durationMs, promptTokens, contextWindow, createdAt, sender, null);
        }

        /** 兼容构造:无 DB 时间戳的内存消息。 */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources) {
            this(role, content, steps, sources, null, null, null, null, null);
        }

        /** 兼容构造:仅耗时(既有测试/调用方)。 */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, java.time.LocalDateTime createdAt) {
            this(role, content, steps, sources, durationMs, null, null, createdAt, null);
        }

        /** 兼容构造:无 sender(旧调用方)。 */
        public StoredMessage(String role, String content, List<ChatStepDto> steps, List<CitationDto> sources,
                             Long durationMs, Integer promptTokens, Long contextWindow,
                             java.time.LocalDateTime createdAt) {
            this(role, content, steps, sources, durationMs, promptTokens, contextWindow, createdAt, null);
        }
    }
}
