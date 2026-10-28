package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;
import com.nora.common.logging.TraceContext;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具调用生命周期：循环检测、权限与审批、执行、终态记录及结果回填。
 * 独立调用可并发执行，结果按模型声明的顺序回填；取消信号同时传给各执行任务。
 *
 * <p>「这次调用看起来是什么」（参数解析、标题、审批载荷、脱敏）由 {@link ToolPresentation} 负责；
 * 本类只决定执行与否以及如何记录结果。
 */
@Component
class ToolStepEmitter {

    private static final Logger log = LoggerFactory.getLogger(ToolStepEmitter.class);

    /** 循环熔断触发前,相同(工具, 参数)指纹的允许重复次数。 */
    private static final int LOOP_WARN_THRESHOLD = 2;
    private static final int LOOP_BLOCK_THRESHOLD = 3;

    private final ObjectMapper objectMapper;
    private final ApprovalService approvalService;
    private final ChatToolExecutor toolExecutor;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final TurnCancellation turnCancellation;
    private final ToolPresentation presentation;

    ToolStepEmitter(ObjectMapper objectMapper, ApprovalService approvalService,
                    ChatToolExecutor toolExecutor, ModelCapabilityRegistry capabilityRegistry, TurnCancellation turnCancellation) {
        this.objectMapper = objectMapper;
        this.approvalService = approvalService;
        this.toolExecutor = toolExecutor;
        this.capabilityRegistry = capabilityRegistry;
        this.turnCancellation = turnCancellation;
        this.presentation = new ToolPresentation(objectMapper, toolExecutor);
    }

    void emitToolCalls(List<ToolExecutionRequest> calls, LoopDetector loopDetector, List<WireMessage> messages,
                       int roundIndex, PermissionMode permissionMode, String sessionId, boolean unattended,
                       ResolvedLlm llm, ChatOrchestrationService.ChatEventConsumer eventConsumer) throws Exception {
        emitToolCalls(calls, loopDetector, messages, roundIndex, permissionMode, sessionId, unattended, llm, eventConsumer, null);
    }

    void emitToolCalls(List<ToolExecutionRequest> calls, LoopDetector loopDetector, List<WireMessage> messages,
                       int roundIndex, PermissionMode permissionMode, String sessionId, boolean unattended,
                       ResolvedLlm llm, ChatOrchestrationService.ChatEventConsumer eventConsumer,
                       com.nora.rag.service.RetrievalService.RetrievalScope scope) throws Exception {
        List<Callable<List<WireMessage>>> tasks = new ArrayList<>();
        Set<String> fingerprints = new java.util.HashSet<>();
        boolean parallel = calls.size() > 1;
        for (ToolExecutionRequest call : calls) {
            String callId = call.id();
            String name = call.name();
            String args = toolExecutor.restrictRetrievalArgs(name, call.arguments(), scope);
            parallel &= canRunInParallel(name, args, permissionMode)
                    && fingerprints.add(canonicalToolName(name) + "|" + canonicalArgs(name, args));
            loopDetector.countAttempt();
            String stepId = "s-call-" + loopDetector.attempts() + "-" + callId;
            tasks.add(() -> {
                if (Thread.currentThread().isInterrupted()
                        || turnCancellation != null && turnCancellation.isCancelled(sessionId)) {
                    throw new CancellationException("turn cancelled");
                }
                List<WireMessage> results = new ArrayList<>();
                emitToolStep(stepId, name, args, loopDetector, results, callId, roundIndex,
                        permissionMode, sessionId, unattended, llm, eventConsumer);
                return results;
            });
        }
        if (!parallel) {
            for (var task : tasks) messages.addAll(task.call());
            return;
        }
        // ponytail: 每轮最多 4 个工具并发;独立 I/O 确实排队时再调整,不引入调度框架。
        try (var pool = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
            List<Future<List<WireMessage>>> futures = new ArrayList<>();
            try {
                for (var task : tasks) futures.add(pool.submit(TraceContext.wrap(task)));
                // 执行和展示按实际完成时间;模型结果按原调用顺序回填,不共享消息列表。
                for (var future : futures) messages.addAll(future.get());
            } finally {
                for (var future : futures) future.cancel(true);
            }
        }
    }

    boolean canRunInParallel(String name, String args, PermissionMode permissionMode) {
        if (permissionMode == PermissionMode.ASK) return false;
        if (TerminalService.isCommandTool(name)) return permissionMode == PermissionMode.FULL;
        if (toolExecutor.classifyRisk(name, args) != RiskClassifier.Risk.LOW) return false;
        return switch (name) {
            case "manage_workspace" -> Set.of("list", "read").contains(
                    RiskClassifier.normalizeWorkspaceAction(presentation.parseArgsSafe(args).path("action").asText("")));
            case "manage_skill" -> Set.of("list", "read").contains(
                    presentation.parseArgsSafe(args).path("action").asText("").trim()
                            .toLowerCase(java.util.Locale.ROOT));
            case "load_tools", "search_knowledge",
                    "manage_file", "read_file", "manage_automation", "manage_datasource",
                    "manage_service", "manage_mcp", "manage_env_vars", "manage_knowledge" -> true;
            default -> false; // 浏览器、写入、交互和未知工具保留串行生命周期。
        };
    }

    /**
     * 执行一次工具调用并发出步骤(pending → running → 终态)。终态状态:
     * 守卫拒绝输入时为 {@code declined}(规则拒绝,非执行错误),
     * 执行抛异常时为 {@code failed},其余为 {@code completed}。
     * ASK 档每次都请求批准;ASSIST 档仅高风险(RiskClassifier)请求批准;
     * 等待批准期间保持 pending；实际调用前才转 running。
     */
    /** 遗留的测试/辅助入口:无会话即绕过审批。 */
    void emitToolStep(String toolStepId, String name, String args,
                              LoopDetector loopDetector,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        emitToolStep(toolStepId, name, args, loopDetector, messages, callId, roundIndex,
                PermissionMode.FULL, null, eventConsumer);
    }

    void emitToolStep(String toolStepId, String name, String args,
                              LoopDetector loopDetector,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        emitToolStep(toolStepId, name, args, loopDetector, messages, callId, roundIndex,
                permissionMode, sessionId, false, null, eventConsumer);
    }

    /** 携带已解析 LLM 的重载,让图片附件能遵循其视觉能力。 */
    void emitToolStep(String toolStepId, String name, String args,
                              LoopDetector loopDetector,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              ResolvedLlm llm,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        emitToolStep(toolStepId, name, args, loopDetector, messages, callId, roundIndex,
                permissionMode, sessionId, false, llm, eventConsumer);
    }

    /**
     * 无人值守重载(2026-09-20,定时任务=往会话发消息):
     * {@code unattended=true} 时——即使有会话也不进交互审批(现场无人),
     * CRITICAL 工具直接拒绝(不等 120s 超时);FULL 档其余工具照常执行。
     */
    void emitToolStep(String toolStepId, String name, String rawArgs,
                              LoopDetector loopDetector,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              boolean unattended,
                              ResolvedLlm llm,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        InvocationResult result = invokeTool(toolStepId, name, rawArgs, loopDetector, roundIndex,
                permissionMode, sessionId, unattended, eventConsumer);
        backfillToolMessage(messages, callId, name, result.outcome().content(), result.outcome().images(), llm);
    }

    record InvocationResult(String status, ChatToolExecutor.ToolOutcome outcome) {}

    /** 标准调用和程序子调用共用审批、执行和落库；模型消息只由各自的外层回填。 */
    InvocationResult invokeTool(String toolStepId, String name, String rawArgs, LoopDetector loopDetector,
                               int roundIndex, PermissionMode permissionMode, String sessionId,
                               boolean unattended, ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        String args = toolExecutor.resolveProjectArgs(name, rawArgs, sessionId);
        ToolPresentation.ParsedArgs parsed = presentation.parseArgs(name, args);
        String displayArgs = presentation.scrubArgsForLog(args, name);
        ToolPresentation.ParsedArgs displayed = args.equals(displayArgs) ? parsed
                : presentation.parseArgs(name, displayArgs);
        // 跨轮历史重建(对齐 Claude Code/Codex「工具链即历史」):step 持久化脱敏后的
        // 原始参数,下一轮把它重放为 wire 层 assistant(tool_calls)+tool(result) 对——
        // 模型看到自己真实的调用记录,而不是"纯文本声称跑过命令"(实测:缺失工具链
        // 结构时弱模型会续写编造工具结果)。脱敏与日志同口径,不存凭据明文。
        ChatStepDto.StepInput input = presentation.withRawArgs(displayed.input(), displayArgs, name);
        // 标题管线(2026-09-29 统一,对齐 Claude Code 的工具标题):
        //   ① 模型经 description 参数填的标题(所有内置工具 schema 已统一注入该参数)
        //   ② 省略时从参数派生「动作 + 目标」(「读取 memory/x.md」,比静态工具名有信息量)
        //   ③ 无法派生回退静态名(defaultTitle)
        String title = displayed.description() != null && !displayed.description().isBlank()
                ? displayed.description() : presentation.derivedTitle(name, displayed);
        long toolStart = System.currentTimeMillis();
        // 审批等待时间不计入工具执行耗时(2026-09-18 复盘):批准后重置锚点,
        // completed 步骤的 duration=纯执行时间;declined 保留全程时长(等的是用户)。
        long[] execStart = {toolStart};
        log.info("tool call: {} (round={}, args={})", name, roundIndex,
                Texts.abbreviate(presentation.scrubArgsForLog(args, name), 200));
        emitStep(eventConsumer, new ChatStepDto(toolStepId, "tool", title,
                null, null, "pending", name, input, null, roundIndex));

        // 循环检测(2026-09-20 改为结果感知,设计 §9.3;验收 F4 修正):
        // 指纹 = 标准工具名 + 规范化参数(JSON 键排序、剔除展示字段 description、
        // 别名归一到标准名)。修正两个方向:
        //   ① 漏拦:此前部分工具用原始 JSON——description(展示标题)变化或
        //      字段顺序变化会重置计数,同一调用换描述就能绕过熔断;
        //   ② 误拦:此前 manage_file 只用 target(id)——同一文件的 read/rename/
        //      move/delete 指纹相同,前一个动作累计到阈值会阻断后一个不同动作。
        // 规范化后:同一次操作(忽略展示差异)指纹一致;不同操作(action/参数
        // 含义不同)指纹不同。
        String fingerprint = canonicalToolName(name) + "|" + canonicalArgs(name, args);
        if (loopDetector.shouldBlock(fingerprint, LOOP_BLOCK_THRESHOLD)) {
            String error = "重复调用已阻断：同样的参数已连续 " + loopDetector.blockCount(fingerprint)
                    + " 次得到相同结果。请基于已有结果继续回答,或换一种查询/诊断方式。";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "重复调用被循环熔断阻断", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            return new InvocationResult("declined", ChatToolExecutor.textOutcome("ERROR: " + error, error));
        }
        if (loopDetector.shouldWarn(fingerprint, LOOP_WARN_THRESHOLD)) {
            log.info("loop warning: {} repeated with unchanged result {} time(s)", name,
                    loopDetector.blockCount(fingerprint));
        }

        RiskClassifier.Risk risk = toolExecutor.classifyRisk(name, args);
        if ((sessionId == null || unattended) && (risk == RiskClassifier.Risk.CRITICAL
                || permissionMode != PermissionMode.FULL && (risk == RiskClassifier.Risk.HIGH || permissionMode == PermissionMode.ASK))) {
            String error = "拒绝执行：" + name + " 需要用户现场批准，无人值守权限为 " + permissionMode
                    + "。请在最终回答中说明待确认的操作目的";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "无人值守通道拒绝执行", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            return new InvocationResult("declined", ChatToolExecutor.textOutcome("ERROR: " + error, error));
        }

        if ("ask_user".equals(name)) {
            var outcome = toolExecutor.executeTool(name, args,
                    new ToolCallContext(sessionId, toolStepId, null, eventConsumer, unattended));
            boolean failed = outcome.content().startsWith("ERROR:");
            loopDetector.recordResult(fingerprint, resultHashOf(outcome.content()));
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(outcome.content(), outcome.summary(), null, null, false,
                            failed ? outcome.content() : null), failed ? "declined" : "completed", roundIndex, eventConsumer);
            return new InvocationResult(failed ? "declined" : "completed", outcome);
        }

        // 审批门:ASK 全问;ASSIST 问 HIGH+CRITICAL;FULL 只问 CRITICAL
        // (不可逆/带外操作,如删数据源、注册纳管命令)。审批状态保存在
        // 服务端(ApprovalService),模型文本中的"同意"不构成批准。
        // unattended=true 时不进此门(无人可答,上面已处理 CRITICAL)。
        if (approvalService != null && sessionId != null && !unattended) {
            boolean needApproval = permissionMode == PermissionMode.ASK
                    || permissionMode == PermissionMode.ASSIST && risk != RiskClassifier.Risk.LOW
                    || permissionMode == PermissionMode.FULL && risk == RiskClassifier.Risk.CRITICAL;
            if (needApproval) {
                ApprovalRequestDto request = presentation.buildApprovalRequest(toolStepId, name, displayed, displayArgs);
                // 持有注册句柄并等待其 future(2026-09-20 修复):不要把 token
                // 传回 await(token) 查表——future 完成回调先删条目,查表等待会
                // 把已批准的操作误判为拒绝(resolve=true → await=false)。
                ApprovalService.Registered registered =
                        approvalService.registerWithFuture(sessionId, toolStepId, request);
                eventConsumer.approvalRequired(registered.ticket());
                boolean approved = approvalService.awaitFuture(registered);
                if (!approved) {
                    finishToolStep(toolStepId, name, title, input, toolStart,
                            new ChatStepDto.StepResult(null, "用户未批准", null, null, false,
                                    "用户未批准该操作,Agent 已跳过执行。如需执行请调整方案或让用户切换权限档位"),
                            "declined", roundIndex, eventConsumer);
                    return new InvocationResult("declined", ChatToolExecutor.textOutcome(
                            "ERROR: 用户拒绝批准该操作。请说明操作目的,或改用无需写权限的方式回答", "用户未批准"));
                }
            }
        }

        // 这条记录持久化成功后才调用工具，启动恢复可据此区分未执行与结果未知。
        if (Thread.currentThread().isInterrupted() || turnCancellation != null && turnCancellation.isCancelled(sessionId))
            throw new CancellationException("turn cancelled before tool execution");
        emitStep(eventConsumer, new ChatStepDto(toolStepId, "tool", title,
                null, null, "running", name, input, null, roundIndex));
        execStart[0] = System.currentTimeMillis();
        ChatToolExecutor.ToolOutcome outcome = toolExecutor.executeTool(name, args, new ToolCallContext(sessionId, toolStepId,
                new ChatToolExecutor.LiveOutput() {
                    /** 文本通道最近一行(run_command 实时输出):原地刷新 detail。 */
                    @Override
                    public void text(String chunk) {
                        // 实时输出流(run_command)/进度文本(fetch_media):同 id step
                        // 原地替换,前端自然刷新(与 reasoning_delta 同款机制)
                        emitStep(eventConsumer, new ChatStepDto(toolStepId, "tool", title,
                                chunk, null, "running", name, input, null, roundIndex));
                    }

                    /** 结构化进度通道(fetch_media):detail 给一行文本兜底,
                     *  progress 供前端渲染进度条/速率/ETA(旧前端忽略未知字段)。 */
                    @Override
                    public void progress(ChatStepDto.StepProgress progress) {
                        emitStep(eventConsumer, new ChatStepDto(toolStepId, "tool", title,
                                renderProgressLine(progress), null, "running",
                                name, input, null, roundIndex, null, progress));
                    }

                    /**
                     * 取消信号(2026-09-17 修复):线程中断 OR TurnCancellation 标志。
                     * 只用中断标志不可靠——实测它在下载返回后的 JDBC/SSE 链路上
                     * 被下游消费,导致「停止生成」杀不掉下载;会话级标志不可吞。
                     */
                    @Override
                    public boolean cancelled() {
                        return Thread.currentThread().isInterrupted()
                                || (turnCancellation != null && sessionId != null
                                    && turnCancellation.isCancelled(sessionId));
                    }
                }, eventConsumer, unattended));
        boolean failure = outcome.content().startsWith("ERROR:");
        // 状态语义(设计 §5.2):结果未知(调用已发出但中断,远端可能已生效)
        // 与普通失败分开——不是"没执行",不能静默当失败重做;
        // 部分成功(批量任务有成功也有失败)同样独立,不显示为全量成功
        String status = outcome.unknown() ? "unknown"
                : outcome.partial() ? "partial"
                : (failure ? "failed" : "completed");
        ChatStepDto.StepResult result = new ChatStepDto.StepResult(
                outcome.content(),
                outcome.summary(),
                outcome.rowCount(),
                countLines(outcome.content()),
                outcome.truncated(),
                failure ? outcome.content() : null,
                outcome.files(), outcome.focusTarget(), outcome.fileErrors(),
                outcome.fileChanges(), outcome.execution());
        // 记录本次结果(结果感知的循环检测,设计 §9.3):结果变化会重置计数——
        // 「正常轮询/有进展的重复读取」不被误拦;结果未知不参与判定
        loopDetector.recordResult(fingerprint, outcome.unknown() ? null
                : outcome.content() == null ? null : resultHashOf(outcome.content()));
        finishToolStep(toolStepId, name, title, input, execStart[0], result, status, roundIndex, eventConsumer);
        return new InvocationResult(status, outcome);
    }

    /**
     * 循环检测的结果指纹(设计 §9.3「判断时使用结果或进度」;验收 F5 修正):
     * 对**完整内容**做 SHA-256 取前 16 位十六进制。
     *
     * <p>此前只比较头尾各 1000 字符 + 长度——中段变化但长度不变时指纹相同,
     * 真实变化被误判为「没有进展」而阻断(验收探针复现:连续更新文件中段数字,
     * 第四轮被 declined)。工具输出最大 30K 字符,整串哈希是微秒级开销,
     * 没有理由做有损采样。
     */
    private static String resultHashOf(String content) {
        if (content == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 必然可用;兜底退化为全串 hashCode(仍比头尾采样强)
            return content.length() + ":" + Integer.toHexString(content.hashCode());
        }
    }

    private void finishToolStep(String toolStepId, String name, String title, ChatStepDto.StepInput input,
                                long toolStart, ChatStepDto.StepResult result, String status,
                                int roundIndex,
                                ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        String summaryLine = result.summary() != null ? result.summary()
                : (result.error() != null ? Texts.abbreviate(result.error(), 160) : null);
        emitStep(eventConsumer, new ChatStepDto(toolStepId, "tool", title,
                summaryLine, System.currentTimeMillis() - toolStart, status, name, input, result, roundIndex));
    }

    /** 结构化进度的人类可读一行(步骤 detail;旧前端按文本展示)。 */
    private static String renderProgressLine(ChatStepDto.StepProgress p) {
        if ("listing".equals(p.phase())) {
            return "正在获取清单…已取到 " + (p.done() == null ? 0 : p.done()) + " 条";
        }
        StringBuilder sb = new StringBuilder("下载中 ")
                .append(p.done() == null ? 0 : p.done()).append('/')
                .append(p.total() == null ? "?" : p.total());
        if (p.active() != null && p.active() > 1) {
            sb.append("(并行 ").append(p.active()).append(')');
        }
        if (p.bytesDone() != null) {
            sb.append(" · ").append(FileTools.formatSize(p.bytesDone()));
            if (p.bytesTotal() != null && p.bytesTotal() > 0) {
                sb.append('/').append(FileTools.formatSize(p.bytesTotal()));
            }
        }
        if (p.bytesPerSec() != null && p.bytesPerSec() > 0) {
            sb.append(" · ").append(FileTools.formatSize(p.bytesPerSec())).append("/s");
        }
        if (p.etaSeconds() != null) {
            sb.append(" · 约剩 ").append(ChatToolExecutor.formatEta(p.etaSeconds()));
        }
        if (p.currentFile() != null) {
            sb.append(" · ").append(Texts.abbreviate(p.currentFile(), 40));
        }
        return sb.toString();
    }

    /** 追加回填给模型的工具结果消息(RespondToModel 风格:错误是内容,不是异常)。 */
    private void backfillToolMessage(List<WireMessage> messages, String callId, String name, String content) {
        backfillToolMessage(messages, callId, name, content, java.util.List.of(), null);
    }

    /**
     * 带可选图片附件的工具结果回填。
     *
     * <p>{@code images} 非空且当前模型能识图时,工具消息携带多模态内容数组
     * (text + image_url 部件)。chat/completions 发送时将图片移到工具批次后
     * 的 user 附件；responses 映射为多模态输出。否则图片被丢弃并附明确说明,纯文本模型绝不静默
     * 假装看过。
     *
     * @param visionOverride TRUE/FALSE = 设置强制;null = 运行时自适应
     */
    void backfillToolMessage(List<WireMessage> messages, String callId, String name, String content,
                                     java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                                     ResolvedLlm llm) {
        boolean wantImages = images != null && !images.isEmpty();
        boolean canSee = wantImages && capabilityRegistry.visionAllowed(llm, images.size());
        if (wantImages && !canSee) content += "\n(注:工具返回 " + images.size() + " 张图片，但当前模型不支持图像输入，图片内容未提供)";
        WireMessage result = WireMessage.tool(callId, name, content);
        if (canSee) result = result.withImages(images);
        messages.add(result);
    }


    /**
     * 工具名归一(验收 F4):兼容别名归到标准名——旧历史/旧调用里的
     * read_file 与 manage_file 视为同一工具,避免新旧名各自计数绕过熔断。
     */
    private static String canonicalToolName(String name) {
        return "read_file".equals(name) ? "manage_file" : name;
    }

    /** 按实际工具语义归一参数,递归排序对象键;MCP 业务字段和数组顺序保留。 */
    private String canonicalArgs(String toolName, String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!(node instanceof ObjectNode obj)) {
                return args;
            }
            boolean mountedMcp = toolName != null && toolName.startsWith("mcp__");
            if (!mountedMcp) {
                // 内置工具:description 是本地注入的展示标题,剔除
                obj.remove("description");
                // action 归一(与执行层同一别名表,验收 F4「标准化动作」):
                // add→create / invoke→call / download→import 等同一执行语义的
                // 拼写必须得到同一指纹,否则模型换拼写即可绕过重复检测
                if (obj.has("action") && obj.get("action").isTextual()) {
                    String rawAction = obj.path("action").asText("");
                    String canonical = switch (toolName == null ? "" : toolName) {
                        case "manage_workspace" -> RiskClassifier.normalizeWorkspaceAction(rawAction);
                        case "manage_datasource" -> RiskClassifier.normalizeDatasourceAction(rawAction);
                        case "manage_service" -> RiskClassifier.normalizeServiceAction(rawAction);
                        case "manage_mcp" -> RiskClassifier.normalizeMcpAction(rawAction);
                        // 其余内置工具:执行层用 trim().toLowerCase() 派发
                        default -> rawAction.trim().toLowerCase(java.util.Locale.ROOT);
                    };
                    obj.put("action", canonical);
                }
            }
            if ("manage_workspace".equals(toolName)) {
                // 寻址字段归一(与执行层共用同一别名序;验收 T2):
                // 缺失/null/空串/空白都按同一回退序 path→filename→file
                String path = RiskClassifier.workspacePathOf(obj);
                obj.remove("path");
                obj.remove("filename");
                obj.remove("file");
                if (path != null && !path.isBlank()) {
                    obj.put("path", path);
                }
            }
            if ("manage_file".equals(toolName) || "read_file".equals(toolName)) {
                // 文件中心寻址归一(同类 T2):与执行层 parseArgs 的提取序完全一致
                // ——id→path→filename(执行层不认 file 别名,这里也不能认:认了
                // 会把「file=a.txt」(实际走 list)与「path=a.txt」(实际 read)并成
                // 同一指纹,制造假合并)。
                // 归一后:数字目标统一存 id(执行层对数字一律按文件中心 id 处理),
                // 非数字统一存 path(id="abc" 与 path="abc" 执行层同走路径路由)。
                String target = obj.path("id").asText(null);
                if (target == null || target.isBlank()) {
                    target = obj.path("path").asText(null);
                }
                if (target == null || target.isBlank()) {
                    target = obj.path("filename").asText(null);
                }
                obj.remove("id");
                obj.remove("path");
                obj.remove("filename");
                if (target != null && !target.isBlank()) {
                    if (target.trim().matches("\\d+")) {
                        obj.put("id", target.trim());
                    } else {
                        obj.put("path", target);
                    }
                }
            }
            if ("manage_mcp".equals(toolName)
                    && "call".equals(obj.path("action").asText(""))) {
                // action 已归一为小写 call;arguments 对象与 JSON 字符串按同一
                // 执行语义解析(验收 T3)——两个入口得到相同指纹
                obj.set("arguments", McpManagementTools.mcpArguments(objectMapper, obj.path("arguments")));
            }
            // 递归规范化(嵌套对象排序;数组顺序保留)
            return canonicalJson(obj).toString();
        } catch (Exception e) {
            return args;
        }
    }

    /**
     * 递归规范化 JSON:对象按键名排序重建,数组逐项规范化(顺序保留),
     * 其余值原样。Jackson 序列化保证字符串转义正确(R4 同类问题的根治思路)。
     */
    private JsonNode canonicalJson(JsonNode node) {
        if (node.isObject()) {
            java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
            node.fieldNames().forEachRemaining(f -> sorted.put(f, canonicalJson(node.get(f))));
            ObjectNode out = objectMapper.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(canonicalJson(item));
            }
            return out;
        }
        return node;
    }

    /** 步骤先脱敏再外发：前端、SSE 与落库共用同一份内容。 */
    private void emitStep(ChatOrchestrationService.ChatEventConsumer consumer, ChatStepDto step) {
        consumer.step(toolExecutor.redactValue(step, ChatStepDto.class));
    }

    /** 暴露行数供 UI 折叠预览("21 lines of output")。 */
    private static Integer countLines(String content) {
        if (content == null) return null;
        return content.split("\n", -1).length;
    }
}
