package com.nora.agent.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatRequest;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.service.ApprovalService;
import com.nora.agent.service.ChatRunService.CompactRequest;
import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.ChatTurnRunner;
import com.nora.agent.service.TurnStreamRegistry;
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

    private final ChatStoreService chatStoreService;
    private final ApprovalService approvalService;
    private final com.nora.agent.service.QuestionService questionService;
    private final TurnStreamRegistry turnStreams;
    private final com.nora.agent.service.SessionProjectService sessionProjects;
    private final com.nora.agent.service.ChatInputQueueService inputQueue;
    private final com.nora.agent.service.ChatRunService runs;

    public AgentController(ChatStoreService chatStoreService, ApprovalService approvalService,
            com.nora.agent.service.QuestionService questionService, TurnStreamRegistry turnStreams,
            com.nora.agent.service.SessionProjectService sessionProjects,
            com.nora.agent.service.ChatInputQueueService inputQueue, com.nora.agent.service.ChatRunService runs) {
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
        this.questionService = questionService;
        this.turnStreams = turnStreams;
        this.sessionProjects = sessionProjects;
        this.inputQueue = inputQueue;
        this.runs = runs;
    }

    @GetMapping("/sessions/{sessionId}/queue")
    public ApiResponse<com.nora.agent.service.ChatInputQueueService.View> queue(@PathVariable String sessionId) {
        return ApiResponse.ok(inputQueue.view(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/queue")
    public ApiResponse<com.nora.agent.service.ChatInputQueueService.View> enqueue(@PathVariable String sessionId,
            @RequestBody com.nora.agent.service.ChatInputQueueService.Input input) {
        if ("compact".equals(input.kind()) && chatStoreService.loadMessages(sessionId).isEmpty())
            throw new IllegalArgumentException("当前会话没有可压缩的历史");
        return ApiResponse.ok(inputQueue.enqueue(sessionId, input));
    }

    @DeleteMapping("/sessions/{sessionId}/queue/{id}")
    public ApiResponse<com.nora.agent.service.ChatInputQueueService.Input> withdraw(@PathVariable String sessionId,
            @PathVariable java.util.UUID id) {
        return ApiResponse.ok(inputQueue.withdraw(sessionId, id));
    }

    @PostMapping("/sessions/{sessionId}/queue/{id}/insert")
    public ApiResponse<Boolean> insertQueued(@PathVariable String sessionId, @PathVariable java.util.UUID id,
                                             @RequestBody InsertRequest request) {
        var input = inputQueue.get(sessionId, id);
        var message = input.request();
        String steeringId = "steer-" + request.turnId() + "-" + id;
        if (chatStoreService.hasSteeringMessage(sessionId, steeringId, message.content(), message.context())) return ApiResponse.ok(true);
        var turn = turnStreams.get(sessionId);
        if (turn == null || !turn.turnId.equals(request.turnId()) || !"chat".equals(turn.operation)
                || !turn.enqueueInput(new TurnStreamRegistry.UserInput(steeringId, message.content(), message.context()),
                        () -> inputQueue.consumeForInsert(sessionId, id, request.turnId()))) throw com.nora.agent.service.ChatRunService.turnInProgress();
        return ApiResponse.ok(true);
    }

    public record InsertRequest(String turnId) {}

    @PostMapping("/sessions/{sessionId}/queue/resume")
    public ApiResponse<Boolean> resumeQueue(@PathVariable String sessionId) {
        inputQueue.pause(sessionId, false);
        return ApiResponse.ok(true);
    }

    @GetMapping("/sessions/{sessionId}/project")
    public ApiResponse<com.nora.agent.service.SessionProjectService.ProjectView> project(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionProjects.view(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/project")
    public ApiResponse<com.nora.agent.service.SessionProjectService.ProjectView> bindProject(@PathVariable String sessionId,
                                                                                           @RequestBody ProjectRequest request) {
        return ApiResponse.ok(runs.withIdleSession(sessionId, () -> {
            chatStoreService.ensureSession(sessionId, "新对话");
            return sessionProjects.bind(sessionId, request.projectRoot());
        }));
    }

    @GetMapping("/sessions/{sessionId}/project/directories")
    public ApiResponse<com.nora.agent.service.SessionProjectService.DirectoryView> projectDirectories(
            @PathVariable String sessionId, @RequestParam(required = false) String path) {
        return ApiResponse.ok(sessionProjects.directories(sessionId, path));
    }

    public record ProjectRequest(String projectRoot) {}
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
                                  @RequestBody ChatRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        return runs.stream(sessionId, request, null);
    }

    @GetMapping("/sessions/{sessionId}/context")
    public ApiResponse<ChatStoreService.ContextView> context(@PathVariable String sessionId) {
        return ApiResponse.ok(chatStoreService.contextView(sessionId));
    }

    @PostMapping(value = "/sessions/{sessionId}/compact", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter compact(@PathVariable String sessionId, @RequestBody CompactRequest request) {
        if (request.requestId() == null) throw new IllegalArgumentException("requestId is required");
        String id = "compact-" + java.util.UUID.fromString(request.requestId());
        String focus = request.focus() == null ? "" : request.focus().trim();
        if (focus.length() > 4000) throw new IllegalArgumentException("focus must not exceed 4000 characters");
        String content = "/compact" + (focus.isEmpty() ? "" : " " + focus);
        var saved = chatStoreService.findCompactRun(id);
        if (saved != null) {
            if (!saved.sessionId().equals(sessionId) || !saved.content().equals(content))
                throw new IllegalArgumentException("requestId already belongs to another operation");
            if (java.util.Set.of("queued", "running", "awaiting_approval", "cancelling").contains(saved.status())) throw com.nora.agent.service.ChatRunService.turnInProgress();
            SseEmitter emitter = new SseEmitter(0L);
                try {
                    boolean completed = "completed".equals(saved.status());
                    emitter.send(SseEmitter.event().name("step").data(new ChatStepDto("s-compaction", "context",
                            completed ? "压缩请求已完成" : "压缩请求已结束", saved.errorMessage(), 0L,
                            completed ? "completed" : "failed", null, null, null, 0), MediaType.APPLICATION_JSON));
                    if (!completed && saved.errorMessage() != null) emitter.send(SseEmitter.event().name("error")
                            .data(java.util.Map.of("message", saved.errorMessage()), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("done").data(new ChatTurnRunner.DonePayload(id, 0L, null, 0,
                            null, null, null, "cancelled".equals(saved.status()), saved.status(), saved.status(), false), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException | IllegalStateException disconnected) { emitter.complete(); }
            return emitter;
        }
        if (chatStoreService.loadMessages(sessionId).isEmpty()) throw new IllegalArgumentException("当前会话没有可压缩的历史");
        return runs.stream(sessionId, new ChatRequest(content, request.model(), request.reasoningLevel(), null,
                request.providerId(), null, "compaction", false, null), request);
    }

    @PostMapping("/sessions/{sessionId}/steer")
    public ApiResponse<Boolean> steer(@PathVariable String sessionId, @RequestBody SteeringRequest request) {
        if (request.content() == null || request.content().isBlank() || request.content().length() > 8000)
            throw new IllegalArgumentException("content is required and must not exceed 8000 characters");
        if (request.turnId() == null || !request.turnId().matches("[0-9a-f]{8}"))
            throw new IllegalArgumentException("turnId is required");
        if (request.messageId() == null || !request.messageId().matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("messageId must be a UUID");
        String messageId = java.util.UUID.fromString(request.messageId()).toString();
        String id = "steer-" + request.turnId() + "-" + messageId;
        String content = request.content().trim();
        if (chatStoreService.hasSteeringMessage(sessionId, id, content, request.context())) return ApiResponse.ok(true);
        TurnStreamRegistry.LiveTurn turn = turnStreams.get(sessionId);
        if (turn == null || !turn.turnId.equals(request.turnId())
                || !turn.enqueueInput(new TurnStreamRegistry.UserInput(id, content, request.context()),
                        () -> chatStoreService.saveSteeringMessage(sessionId, id, content, request.context()))) {
            throw new com.nora.common.exception.BusinessException(
                    com.nora.common.exception.ErrorCategory.CONFLICT, "TURN_FINISHED",
                    "The requested turn is no longer accepting input", null);
        }
        return ApiResponse.ok(true);
    }

    public record SteeringRequest(String turnId, String messageId, String content, com.nora.agent.dto.TaskContext context) {}

    @PostMapping("/sessions/{sessionId}/cancel")
    public ApiResponse<Boolean> cancelTurn(@PathVariable String sessionId) {
        return ApiResponse.ok(runs.cancel(sessionId));
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

    /**
     * 回答挂起的 ask_user 提问(2026-09-29)。一次性 token 来自
     * question_required SSE 事件;答案作为工具结果回填给模型,同一轮继续执行。
     * 未知/已消费 token 返回 404(绝不静默成功)。
     */
    @PostMapping("/answers/{questionToken}")
    public ApiResponse<Boolean> resolveQuestion(@PathVariable String questionToken,
                                                @RequestBody QuestionAnswer answer,
                                                @org.springframework.web.bind.annotation.RequestParam("sessionId") String sessionId) {
        boolean resolved = questionService != null
                && questionService.resolve(sessionId, questionToken, answer.answer());
        if (!resolved) {
            throw new IllegalArgumentException("question not found, session mismatch, already resolved, or blank answer");
        }
        return ApiResponse.ok(true);
    }

    /** 某会话的挂起提问(重连兜底)。 */
    @GetMapping("/sessions/{sessionId}/questions")
    public ApiResponse<List<com.nora.agent.dto.QuestionRequestDto>> pendingQuestions(@PathVariable String sessionId) {
        return ApiResponse.ok(questionService == null ? List.of() : questionService.pendingFor(sessionId));
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

    @GetMapping("/sessions/search")
    public ApiResponse<ChatStoreService.SessionPage> searchSessions(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int offset,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(chatStoreService.searchSessions(q, offset, limit));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ChatStoreService.SessionSummary> session(@PathVariable String sessionId) {
        return ApiResponse.ok(chatStoreService.session(sessionId));
    }

    @GetMapping("/pending-actions")
    public ApiResponse<List<com.nora.agent.dto.PendingActionDto>> pendingActions() {
        return ApiResponse.ok(java.util.stream.Stream.concat(approvalService.pendingActions().stream(),
                        questionService.pendingActions().stream())
                .sorted(java.util.Comparator.comparing(com.nora.agent.dto.PendingActionDto::sessionId)
                        .thenComparing(com.nora.agent.dto.PendingActionDto::stepId)).toList());
    }

    /** 删除会话及其消息。 */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        runs.withIdleSession(sessionId, () -> chatStoreService.deleteSession(sessionId));
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
        int deleted = runs.withIdleSession(sessionId, () -> chatStoreService.truncateFrom(sessionId, index));
        if (deleted < 0) {
            throw new IllegalArgumentException("message index out of range: " + index);
        }
        return ApiResponse.ok(deleted);
    }

    /** POST /api/chat/approvals/{token} 请求体。 */
    public record ApprovalDecision(Boolean approved) {
    }

    /** POST /api/chat/answers/{token} 请求体(ask_user 的回答)。 */
    public record QuestionAnswer(String answer) {
    }

}
