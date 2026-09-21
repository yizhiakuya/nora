package com.nora.agent.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.service.ApprovalService;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.PermissionMode;
import com.nora.agent.service.TurnCancellation;
import com.nora.agent.service.TurnStreamRegistry;
import com.nora.common.logging.TraceContext;
import com.nora.common.response.ApiResponse;

/**
 * 对话端点:SSE 流式(step/delta/sources/done)+ 会话历史,
 * 与前端 agentApi.ts 契约一致。
 *
 * <p>断线重连子系统(live 探测 / SSE 续传)拆至 {@link TurnStreamController}
 * (2026-09-17):本类只管「发起对话 + 会话管理 + 审批」。
 */
@RestController
@RequestMapping("/api/chat")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * 对话轮次 SSE 流的超时(30 分钟;2026-09-21 从 180s 放宽)。
     *
     * <p>为什么:Spring/Tomcat 的 async 超时是**总时长**(不因持续写事件而重置),
     * 此前 180s 会让长轮次(实测 fetch_media 批量任务跑了 470s)在 180s 处被
     * 强制 complete——之后所有 step/delta/done 推送全部失败(日志实测
     * "ResponseBodyEmitter has already completed"),主 SSE 流提前断开,只能靠
     * 前端接续流兜底。30 分钟覆盖已知最长轮次并留足余量;更长的极端任务仍由
     * TurnStreamRegistry 断线重连协议兜底。
     */
    private static final long CHAT_SSE_TIMEOUT_MS = 30 * 60_000L;

    /** 一次性 agent 运行({@code /agent/run})的超时:同步机对机端点,调用方自带超时,保持 180s。 */
    private static final long AGENT_RUN_TIMEOUT_MS = 180_000;

    private final ChatOrchestrationService orchestrationService;
    private final ChatStoreService chatStoreService;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final TurnStreamRegistry turnStreams;
    /** 轮次取消信号(可空:老测试构造器不接);「停止生成」的可靠判据。 */
    private final TurnCancellation turnCancellation;
    /** 轮次执行引擎(从本类拆出,2026-09-17)。 */
    private final ChatTurnRunner turnRunner;
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();
    /** 进行中的轮次,按会话注册;「停止生成」据此中断上游 HTTP 读(省 token) */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.Future<?>> activeTurns =
            new java.util.concurrent.ConcurrentHashMap<>();


    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ObjectMapper objectMapper) {
        this(orchestrationService, chatStoreService, null, objectMapper, new TurnStreamRegistry(), null);
    }

    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ApprovalService approvalService,
                           ObjectMapper objectMapper,
                           TurnStreamRegistry turnStreams) {
        this(orchestrationService, chatStoreService, approvalService, objectMapper, turnStreams, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ApprovalService approvalService,
                           ObjectMapper objectMapper,
                           TurnStreamRegistry turnStreams,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           TurnCancellation turnCancellation) {
        this.orchestrationService = orchestrationService;
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
        this.turnStreams = turnStreams;
        this.turnCancellation = turnCancellation;
        this.turnRunner = new ChatTurnRunner(orchestrationService, chatStoreService, turnStreams, objectMapper,
                turnCancellation);
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * 发送一条用户消息,以 SSE 事件流式返回 agent 回答:
     * {@code step}、{@code delta}、{@code sources}、{@code done},
     * 高风险工具调用需要用户决策时加 {@code approval_required}
     * (三档权限:ask/assist/full)。
     *
     * @param sessionId 会话 id
     * @param request   {@code {content, model, reasoningLevel, permissionMode}}
     * @return SSE 流
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String sessionId,
                                  @RequestBody MessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        // 单会话单轮次(2026-09-20 修复):此前 activeTurns.put 直接覆盖句柄,
        // 两个标签页并发发送会双轮并行执行、取消只控其一(隔离检查确认)。
        // 现在用原子占位拒绝并发提交——重复请求返回冲突,不产生第二个编排线程。
        java.util.concurrent.FutureTask<?> handle = new java.util.concurrent.FutureTask<>(() -> null);
        java.util.concurrent.Future<?> existing = activeTurns.putIfAbsent(sessionId, handle);
        if (existing != null) {
            throw new com.nora.common.exception.BusinessException(
                    com.nora.common.exception.ErrorCategory.CONFLICT,
                    "TURN_IN_PROGRESS",
                    "该会话已有进行中的轮次,请等待完成或先停止生成",
                    "在界面上点击「停止生成」,或等当前回答结束后再发送");
        }
        SseEmitter emitter = new SseEmitter(CHAT_SSE_TIMEOUT_MS);
        // 轮次入口:sessionId + turnId 进 MDC(经 TraceContext.wrap 传播到 chatExecutor
        // 线程),本轮编排/落库/SSE 发送的全部日志自动携带,排障时按会话一屏串联
        String turnId = TraceContext.newTraceId();
        chatExecutor.execute(TraceContext.wrap(() -> {
            // 兜底:无论哪条异常路径,占位/任务句柄都不残留——残留 = 该会话被
            // 永久锁死(后续发送全部被并发闸拒绝)
            try {
                TraceContext.setSessionId(sessionId);
                TraceContext.setTurnId(turnId);
                log.info(">> turn start (contentChars={})", request.content().length());
                // ran 标记:区分「取消先于 run 到达(FutureTask.cancel 后 run 空转,
                // 无任何事件发出)」与「运行中被取消(runChatTurn 自己发 done(stopped))」
                java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                    ran.set(true);
                    turnRunner.runChatTurn(sessionId, request.content().trim(),
                            request.model(), request.reasoningLevel(),
                            PermissionMode.parse(request.permissionMode()), request.providerId(),
                            request.context(),
                            // 发送者(定时任务=往会话发消息):automation 标进消息落库
                            request.sender() == null || request.sender().isBlank() ? "user" : request.sender().trim(),
                            Boolean.TRUE.equals(request.unattended()),
                            request.sessionTitle(),
                            emitter);
                    return null;
                });
                // 占位 → 真实任务的替换(取消端点按会话取句柄中断上游读)。
                // 用 replace(期望值)做原子检查:占位仍在 = 正常启动(R05 后
                // 取消不再移除占位,该分支仅作防御)。
                if (!activeTurns.replace(sessionId, handle, task)) {
                    // 占位已被外部移除(防御路径):不启动编排,发终态收敛 UI
                    if (turnCancellation != null) {
                        turnCancellation.clear(sessionId);
                    }
                    try {
                        emitter.send(SseEmitter.event().name("done")
                                .data("{\"stopped\":true}", MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException ignored) {
                        // 客户端已离开
                    }
                    emitter.complete();
                    return;
                }
                // 取消先于启动到达(R05 修正):仅当**本轮占位句柄**被 cancelTurn
                // 取消时才判"已取消"——句柄语义精确到本轮。不能用
                // turnCancellation.isCancelled(sessionId):它是会话级标志,
                // 上一轮收尾后可能残留(取消到达时上一轮刚好完成),会把**新轮**
                // 误判为已取消(实测:sess-r05b 第二轮收到空 done(stopped))。
                // 标志的清理与消费仍由 runChatTurn 的 begin/finally 负责。
                if (handle.isCancelled()) {
                    activeTurns.remove(sessionId, task);
                    try {
                        emitter.send(SseEmitter.event().name("done")
                                .data("{\"stopped\":true}", MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException ignored) {
                        // 客户端已离开
                    }
                    emitter.complete();
                    return;
                }
                try {
                    task.run();
                } finally {
                    // R05:占位在轮次**实际结束**时释放(取消只中断、不移除);
                    // 新轮在此之前仍被并发闸拒绝,不会与旧轮并行
                    activeTurns.remove(sessionId, task);
                    // 取消先于 run 到达(run 空转、编排从未启动):补发终态
                    if (!ran.get()) {
                        if (turnCancellation != null) {
                            turnCancellation.clear(sessionId);
                        }
                        try {
                            emitter.send(SseEmitter.event().name("done")
                                    .data("{\"stopped\":true}", MediaType.APPLICATION_JSON));
                        } catch (IOException | IllegalStateException ignored) {
                            // 客户端已离开
                        }
                        emitter.complete();
                    }
                }
            } finally {
                activeTurns.remove(sessionId, handle);
                TraceContext.clear();
            }
        }));
        return emitter;
    }

    /** 一次性 agent 运行专用线程池:chat() 是同步阻塞的,提交到这里才能超时可取消 */
    private final ExecutorService agentRunExecutor = Executors.newCachedThreadPool();

    /**
     * POST /api/chat/agent/run — 供服务间调用(automation 动作)的非流式一次性
     * agent 运行。走同一套编排(RAG + 工具循环),FULL 权限档、无审批门(无会话),
     * 静默收集事件,返回最终回答 + token 用量;以 SSE 超时为上限,超时会真正
     * 中断编排线程使上游 LLM 读中止。
     *
     * <p><b>定位(2026-09-20 用户明确)</b>:定时任务不走本端点——定时任务 =
     * 「往会话发一条消息」,直接复用正常对话端点
     * ({@code POST /sessions/{id}/messages},见 automation-service ActionExecutor);
     * 本端点只服务无会话的机对机一次性运行(如 env-service 的 AI 诊断),不落库。
     *
     * <p>响应 {@code {status:"completed", answer, usage}} 或
     * {@code {status:"error", error}};status 字段让调用方无需靠答案文本猜测成败。
     */
    @PostMapping("/agent/run")
    public Map<String, Object> runAgent(@RequestBody AgentRunRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (request.prompt().trim().length() > 8000) {
            throw new IllegalArgumentException("prompt exceeds 8000 characters");
        }
        // chat() 内部是同步执行并返回已完成的 future,用 join() 取值;
        // 包一层 Future 才能实现超时取消(中断编排线程使上游 LLM 读中止)。
        // permissionMode 缺省 FULL(automation 场景);只读分析场景显式传 ASSIST
        PermissionMode mode = request.permissionMode() == null || request.permissionMode().isBlank()
                ? PermissionMode.FULL
                : PermissionMode.parse(request.permissionMode());
        java.util.concurrent.Future<ChatOrchestrationService.ChatTurn> future =
                agentRunExecutor.submit(TraceContext.wrap(() -> orchestrationService.chat(
                        request.prompt().trim(),
                        List.of(), List.of(),
                        request.model(), request.reasoningLevel(),
                        mode,
                        null,
                        SILENT_CONSUMER).join()));
        try {
            ChatOrchestrationService.ChatTurn turn = future.get(AGENT_RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "completed");
            out.put("answer", turn.answer());
            if (turn.usage() != null) {
                out.put("usage", Map.of(
                        "inputTokens", Objects.requireNonNullElse(turn.usage().inputTokens(), 0),
                        "outputTokens", Objects.requireNonNullElse(turn.usage().outputTokens(), 0),
                        "totalTokens", Objects.requireNonNullElse(turn.usage().totalTokens(), 0)));
            }
            return out;
        } catch (TimeoutException e) {
            future.cancel(true); // 中断编排线程 → 上游 LLM HTTP 读中止,不再白烧 token
            throw new IllegalStateException("agent run timed out after " + AGENT_RUN_TIMEOUT_MS + "ms");
        } catch (CancellationException e) {
            throw new IllegalStateException("agent run was cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志,不吞信号
            throw new IllegalStateException("agent run interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("agent run failed: " + cause.getMessage(), cause);
        }
    }

    /** 静默消费全部编排事件:机对机运行没有 SSE 流可发 */
    private static final ChatOrchestrationService.ChatEventConsumer SILENT_CONSUMER =
            new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> found) { }
            };

    /** POST /api/chat/agent/run 请求体。permissionMode 缺省 FULL;只读分析场景传 ASSIST。 */
    public record AgentRunRequest(String prompt, String model, String reasoningLevel, String permissionMode) {
    }

    /**
     * 取消某会话进行中的轮次:中断编排线程使上游 LLM HTTP 读中止(不再白烧
     * token),并清掉挂起的审批。无进行中轮次时幂等返回 ok(false)。
     *
     * <p>取消信号双轨(2026-09-17 修复):{@code future.cancel(true)} 只对
     * 「上游阻塞读」类取消有效;大批量工具(fetch_media)里线程中断标志会被
     * 下游(JDBC/SSE)意外消费——所以同时置 {@link TurnCancellation} 会话
     * 标志,编排循环与批量下载器轮询它,保证「停止生成」一定生效。
     *
     * <p><b>R05(2026-09-20 修复)</b>:此前立即 {@code activeTurns.remove} ——
     * 新轮可在旧轮工作尚未完全退出前启动(并发占位形同虚设,且旧轮收尾的
     * {@code remove(sessionId, handle)} 可能误清新轮的记录)。现在取消只
     * **中断**句柄,占位保留;由轮次自己的 finally 在真正结束时移除
     * (见 sendMessage 的收尾)。新轮在此期间仍会被并发闸以 409 拒绝,
     * 用户需等旧轮退出(通常秒级;前端「正在停止」态即此语义)。
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ApiResponse<Boolean> cancelTurn(@PathVariable String sessionId) {
        if (turnCancellation != null) {
            turnCancellation.request(sessionId);
        }
        // 只中断、不移除:占位由轮次自身的 finally 释放(R05)。移除会让
        // 新轮在旧轮工作退出前拿到占位并行执行,也让旧轮收尾误清新轮。
        var future = activeTurns.get(sessionId);
        boolean cancelled = future != null;
        if (cancelled) {
            future.cancel(true);
            // 运行状态推进(M3-02):cancelling = 已请求停止、工作尚未全部退出。
            // 终态(cancelled)由轮次收尾写入;此处的中间态让任务页显示「正在停止」。
            try {
                chatStoreService.markSessionRunsCancelling(sessionId);
            } catch (Exception e) {
                log.debug("mark cancelling failed for {}: {}", sessionId, e.getMessage());
            }
        }
        approvalService.clearPending(sessionId);
        // 有在途轮次或标志已置,都算"取消已受理"(前端据 ok(true) 收敛 UI)
        return ApiResponse.ok(cancelled || turnCancellation != null);
    }

    /**
     * 批准或拒绝挂起的高风险操作。一次性 token 来自 approval_required SSE 事件;
     * 未知/已消费 token 返回 404(绝不静默成功)。
     */
    @PostMapping("/approvals/{approvalToken}")
    public ApiResponse<Boolean> resolveApproval(@PathVariable String approvalToken,
                                                @RequestBody ApprovalDecision decision,
                                                @org.springframework.web.bind.annotation.RequestParam("sessionId") String sessionId) {
        boolean approved = Boolean.TRUE.equals(decision.approved());
        boolean resolved = approvalService.resolve(sessionId, approvalToken, approved);
        if (!resolved) {
            throw new IllegalArgumentException("approval not found, session mismatch, or already resolved");
        }
        return ApiResponse.ok(approved);
    }

    /** 某会话的挂起审批(重连兜底)。 */
    @GetMapping("/sessions/{sessionId}/approvals")
    public ApiResponse<List<ApprovalRequestDto>> pendingApprovals(@PathVariable String sessionId) {
        return ApiResponse.ok(approvalService.pendingFor(sessionId));
    }

    /** 某会话的对话历史,旧到新(前端 ChatMessage[])。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatStoreService.StoredMessage> messages(@PathVariable String sessionId) {
        return chatStoreService.loadMessages(sessionId);
    }

    /** 全部会话,最近活跃在前(前端侧栏列表)。 */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatStoreService.SessionSummary>> sessions() {
        return ApiResponse.ok(chatStoreService.listSessions());
    }

    /** 删除会话及其消息。 */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        if (!chatStoreService.deleteSession(sessionId)) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        return ApiResponse.ok();
    }

    /**
     * 从 {@code index} 处的消息(0 起、按时间序)起含自身截断历史。供前端
     * "编辑并重发"流程:用户回退到较早的用户消息、编辑后重发——服务端上下文
     * 必须一致,所以旧分支(该消息及其后全部)先被删除。
     */
    @DeleteMapping("/sessions/{sessionId}/messages/{index}")
    public ApiResponse<Integer> truncateFrom(@PathVariable String sessionId,
                                             @PathVariable int index) {
        int deleted = chatStoreService.truncateFrom(sessionId, index);
        if (deleted < 0) {
            throw new IllegalArgumentException("message index out of range: " + index);
        }
        return ApiResponse.ok(deleted);
    }











    /** POST /api/chat/sessions/{id}/messages body. providerId = 前端选定的渠道(同名模型跨渠道时精确定位)。 */
    public record MessageRequest(String content, String model, String reasoningLevel, String permissionMode,
                                 Long providerId,
                                 /** 结构化任务上下文(M2-01,可空):refs/output/origin/dataSelection。 */
                                 com.nora.agent.dto.TaskContext context,
                                 /** 发送者(2026-09-20):null/"user"=用户;automation=定时任务(气泡显示「定时任务」)。 */
                                 String sender,
                                 /** 无人值守(true=CRITICAL 直接拒绝,不等交互审批;定时任务用)。 */
                                 Boolean unattended,
                                 /** 定时会话标题(仅 sender=automation 时使用:「定时任务:规则名」)。 */
                                 String sessionTitle) {
        public MessageRequest(String content, String model, String reasoningLevel) {
            this(content, model, reasoningLevel, null, null, null, null, null, null);
        }

        public MessageRequest(String content, String model, String reasoningLevel, String permissionMode) {
            this(content, model, reasoningLevel, permissionMode, null, null, null, null, null);
        }
    }

    /** POST /api/chat/approvals/{token} 请求体。 */
    public record ApprovalDecision(Boolean approved) {
    }




}
