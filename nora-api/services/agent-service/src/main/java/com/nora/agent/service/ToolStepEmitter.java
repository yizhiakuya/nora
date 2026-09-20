package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;

/**
 * 工具步骤发射器(2026-09-17 从 ChatOrchestrationService 拆出,拆分方案收尾):
 * emitToolStep 的完整生命周期(参数解析 → 循环熔断 → 无人值守闸 → 审批门 →
 * 执行 → 终态步骤 + 结果回填)+ 审批请求构建 + args 脱敏/重放。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class ToolStepEmitter {

    private static final Logger log = LoggerFactory.getLogger(ToolStepEmitter.class);

    /** 循环熔断触发前,相同(工具, 参数)指纹的允许重复次数。 */
    private static final int LOOP_WARN_THRESHOLD = 2;
    private static final int LOOP_BLOCK_THRESHOLD = 3;

    private final ObjectMapper objectMapper;
    /** 审批门(可空=测试构造器不接,无审批直接执行)。 */
    private final ApprovalService approvalService;
    private final ChatToolExecutor toolExecutor;
    private final ModelCapabilityRegistry capabilityRegistry;
    /** 轮次取消信号(可空=测试构造器不接);批量工具用它做不可吞的取消检查。 */
    private final TurnCancellation turnCancellation;

    ToolStepEmitter(ObjectMapper objectMapper, ApprovalService approvalService,
                    ChatToolExecutor toolExecutor, ModelCapabilityRegistry capabilityRegistry) {
        this(objectMapper, approvalService, toolExecutor, capabilityRegistry, null);
    }

    ToolStepEmitter(ObjectMapper objectMapper, ApprovalService approvalService,
                    ChatToolExecutor toolExecutor, ModelCapabilityRegistry capabilityRegistry,
                    TurnCancellation turnCancellation) {
        this.objectMapper = objectMapper;
        this.approvalService = approvalService;
        this.toolExecutor = toolExecutor;
        this.capabilityRegistry = capabilityRegistry;
        this.turnCancellation = turnCancellation;
    }

    /**
     * 执行一次工具调用并发出结构化步骤对(running → 终态)。终态状态:
     * 守卫拒绝输入时为 {@code declined}(规则拒绝,非执行错误),
     * 执行抛异常时为 {@code failed},其余为 {@code completed}。
     * ASK 档每次都请求批准;ASSIST 档仅高风险(RiskClassifier)请求批准;
     * FULL 档不询问。等待批准期间 step 保持 running,批准事件由 SSE 下发。
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
    void emitToolStep(String toolStepId, String name, String args,
                              LoopDetector loopDetector,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              boolean unattended,
                              ResolvedLlm llm,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        ParsedArgs parsed = parseArgs(name, args);
        // 跨轮历史重建(对齐 Claude Code/Codex「工具链即历史」):step 持久化脱敏后的
        // 原始参数,下一轮把它重放为 wire 层 assistant(tool_calls)+tool(result) 对——
        // 模型看到自己真实的调用记录,而不是"纯文本声称跑过命令"(实测:缺失工具链
        // 结构时弱模型会续写编造工具结果)。脱敏与日志同口径,不存凭据明文。
        ChatStepDto.StepInput input = withRawArgs(parsed.input(), args);
        // Claude Code 模式:模型经 description 参数填展示标题(祈使句、无主观词);
        // 省略时回退工具名
        String title = parsed.description() != null && !parsed.description().isBlank()
                ? parsed.description() : defaultTitle(name);
        long toolStart = System.currentTimeMillis();
        // 审批等待时间不计入工具执行耗时(2026-09-18 复盘):批准后重置锚点,
        // completed 步骤的 duration=纯执行时间;declined 保留全程时长(等的是用户)。
        long[] execStart = {toolStart};
        log.info("tool call: {} (round={}, args={})", name, roundIndex,
                Texts.abbreviate(scrubArgsForLog(args), 200));
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                null, null, "running", name, input, null, roundIndex));

        // 循环检测(2026-09-20 改为结果感知,设计 §9.3;验收 F4 修正):
        // 指纹 = 标准工具名 + 规范化参数(JSON 键排序、剔除展示字段 description、
        // 别名归一到标准名)。修正两个方向:
        //   ① 漏拦:此前部分工具用原始 JSON——description(展示标题)变化或
        //      字段顺序变化会重置计数,同一调用换描述就能绕过熔断;
        //   ② 误拦:此前 manage_file 只用 target(id)——同一文件的 read/rename/
        //      move/delete 指纹相同,前一个动作累计到阈值会阻断后一个不同动作。
        // 规范化后:同一次操作(忽略展示差异)指纹一致;不同操作(action/参数
        // 含义不同)指纹不同。
        String fingerprint = canonicalToolName(name) + "|" + canonicalArgs(args);
        if (loopDetector.shouldBlock(fingerprint, LOOP_BLOCK_THRESHOLD)) {
            String error = "重复调用已阻断：同样的参数已连续 " + loopDetector.blockCount(fingerprint)
                    + " 次得到相同结果。请基于已有结果继续回答,或换一种查询/诊断方式。";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "重复调用被循环熔断阻断", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            backfillToolMessage(messages, callId, "ERROR: " + error);
            return;
        }
        if (loopDetector.shouldWarn(fingerprint, LOOP_WARN_THRESHOLD)) {
            log.info("loop warning: {} repeated with unchanged result {} time(s)", name,
                    loopDetector.blockCount(fingerprint));
        }

        // 无人值守闸(2026-09-20 扩展):sessionId 为 null(旧 /agent/run)或
        // unattended=true(定时任务会话,现场无人)时,审批门形同虚设——
        // CRITICAL(不可逆/带外操作)一律拒绝,不等交互审批;模型收到引导文案。
        // 有会话的无人值守仍落库(步骤/回答进会话,用户事后可查)。
        if ((sessionId == null || unattended)
                && RiskClassifier.classify(name, args) == RiskClassifier.Risk.CRITICAL) {
            String error = "拒绝执行：" + name + " 属于不可逆操作(删除/注册类),只能在有人值守的聊天对话中执行"
                    + "(用户需亲自批准)。请把这一结论连同操作目的返回给调用方";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "无人值守通道拒绝执行", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            backfillToolMessage(messages, callId, "ERROR: " + error);
            return;
        }

        // 审批门:ASK 全问;ASSIST 问 HIGH+CRITICAL;FULL 只问 CRITICAL
        // (不可逆/带外操作,如删数据源、注册纳管命令)。审批状态保存在
        // 服务端(ApprovalService),模型文本中的"同意"不构成批准。
        // unattended=true 时不进此门(无人可答,上面已处理 CRITICAL)。
        if (approvalService != null && sessionId != null && !unattended) {
            RiskClassifier.Risk risk = RiskClassifier.classify(name, args);
            boolean needApproval = permissionMode == PermissionMode.ASK
                    || permissionMode == PermissionMode.ASSIST && risk != RiskClassifier.Risk.LOW
                    || permissionMode == PermissionMode.FULL && risk == RiskClassifier.Risk.CRITICAL;
            if (needApproval) {
                ApprovalRequestDto request = buildApprovalRequest(toolStepId, name, parsed, permissionMode, args);
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
                    backfillToolMessage(messages, callId,
                            "ERROR: 用户拒绝批准该操作。请说明操作目的,或改用无需写权限的方式回答");
                    return;
                }
                // 批准后重新发出 running(用户等待期间 step 可能显示为等待批准态),
                // 并把执行计时锚点重置到此刻——完成态的 duration 只反映真实执行
                eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                        null, null, "running", name, input, null, roundIndex));
                execStart[0] = System.currentTimeMillis();
            }
        }

        ChatToolExecutor.ToolOutcome outcome = toolExecutor.executeTool(name, args, parsed,
                new ChatToolExecutor.LiveOutput() {
                    /** 文本通道最近一行(run_command 实时输出):原地刷新 detail。 */
                    @Override
                    public void text(String chunk) {
                        // 实时输出流(run_command)/进度文本(fetch_media):同 id step
                        // 原地替换,前端自然刷新(与 reasoning_delta 同款机制)
                        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                                chunk, null, "running", name, input, null, roundIndex));
                    }

                    /** 结构化进度通道(fetch_media):detail 给一行文本兜底,
                     *  progress 供前端渲染进度条/速率/ETA(旧前端忽略未知字段)。 */
                    @Override
                    public void progress(ChatStepDto.StepProgress progress) {
                        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
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
                });
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
                failure ? outcome.content() : null);
        // 记录本次结果(结果感知的循环检测,设计 §9.3):结果变化会重置计数——
        // 「正常轮询/有进展的重复读取」不被误拦;结果未知不参与判定
        loopDetector.recordResult(fingerprint, outcome.unknown() ? null
                : outcome.content() == null ? null : resultHashOf(outcome.content()));
        finishToolStep(toolStepId, name, title, input, execStart[0], result, status, roundIndex, eventConsumer);
        backfillToolMessage(messages, callId, outcome.content(), outcome.images(), llm);
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
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
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
            sb.append(" · ").append(FileToolClient.formatSize(p.bytesDone()));
            if (p.bytesTotal() != null && p.bytesTotal() > 0) {
                sb.append('/').append(FileToolClient.formatSize(p.bytesTotal()));
            }
        }
        if (p.bytesPerSec() != null && p.bytesPerSec() > 0) {
            sb.append(" · ").append(FileToolClient.formatSize(p.bytesPerSec())).append("/s");
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
    private void backfillToolMessage(List<WireMessage> messages, String callId, String content) {
        backfillToolMessage(messages, callId, content, java.util.List.of(), null);
    }

    /**
     * 带可选图片附件的工具结果回填。
     *
     * <p>{@code images} 非空且当前模型能识图时,工具消息携带多模态内容数组
     * (text + image_url 部件)——chat/completions 与 responses 两种协议均已
     * 对中继端到端验证。否则图片被丢弃并附明确说明,纯文本模型绝不静默
     * 假装看过。
     *
     * @param visionOverride TRUE/FALSE = 设置强制;null = 运行时自适应
     */
    private void backfillToolMessage(List<WireMessage> messages, String callId, String content,
                                     java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                                     ResolvedLlm llm) {
        ObjectNode toolMsg = objectMapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", callId);
        boolean wantImages = images != null && !images.isEmpty();
        boolean canSee = wantImages && capabilityRegistry.visionAllowed(llm, images.size());
        if (!wantImages) {
            toolMsg.put("content", content);
        } else if (canSee) {
            ArrayNode parts = toolMsg.putArray("content");
            ObjectNode textPart = parts.addObject();
            textPart.put("type", "text");
            textPart.put("text", content);
            for (McpServerService.McpToolResult.ImageBlock img : images) {
                ObjectNode imgPart = parts.addObject();
                imgPart.put("type", "image_url");
                imgPart.putObject("image_url")
                        .put("url", "data:" + img.mimeType() + ";base64," + img.base64Data());
            }
        } else {
            // 模型不支持识图(或探测判定不可用):图片不参与上下文,明确告知而非静默丢弃
            toolMsg.put("content", content + "\n(注:本次工具返回了 " + images.size()
                    + " 张图片,但当前模型不支持图像输入,图片内容未提供;如需看图请在设置中改用支持识图的模型)");
        }
        messages.add(new WireMessage(toolMsg));
    }

    /** 解析后的工具调用:带类型入参 + 模型写的展示标题。 */
    record ParsedArgs(ChatStepDto.StepInput input, String description, String containerAction,
                      /** manage_datasource / manage_service 的 action 子命令 */
                      String datasourceAction) {

        /** 兼容构造:无 manage action 的调用点。 */
        ParsedArgs(ChatStepDto.StepInput input, String description, String containerAction) {
            this(input, description, containerAction, null);
        }
    }

    /** 把工具参数解析为前端展示的带类型入参 + 展示标题。 */
    private ParsedArgs parseArgs(String toolName, String argsJson) {
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            String description = node.path("description").asText(null);
            if (description != null && description.length() > 120) {
                description = description.substring(0, 120);
            }
            String action = node.path("action").asText(null);
            switch (toolName) {
                case "execute_sql", "execute_write_sql" -> {
                    return new ParsedArgs(new ChatStepDto.StepInput(node.path("sql").asText(null), null, null,
                                    node.path("datasource").asText(null)),
                            description, null);
                }
                case "manage_datasource", "manage_service" -> {
                    String target = node.has("name") ? node.path("name").asText(null)
                            : node.has("id") ? node.path("id").asText(null)
                            : node.path("target").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "manage_file", "read_file" -> {
                    // 寻址参数:数字 id 为主;模型常写 path(路径路由,2026-09-18
                    // 文件工具分析)或 filename 别名——都提取到 target 交给执行层路由
                    String target = node.path("id").asText(null);
                    if (target == null || target.isBlank()) {
                        target = node.path("path").asText(null);
                    }
                    if (target == null || target.isBlank()) {
                        target = node.path("filename").asText(null);
                    }
                    Integer limit = node.has("limit") && node.get("limit").isNumber()
                            ? node.get("limit").asInt() : null;
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, limit, target),
                            description, null, action);
                }
                case "manage_workspace" -> {
                    // 展示路径与权限判定/执行层同一别名序(2026-09-20);根目录 list 无 path,用 dir 兜底
                    String p1 = RiskClassifier.workspacePathOf(node);
                    if (p1 == null || p1.isBlank()) {
                        p1 = node.path("dir").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, p1),
                            description, null, action);
                }
                case "fetch_media" -> {
                    // 展示目标文件夹(无则 imports);审批卡与折叠行都显示它
                    String folder = node.path("folder").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            folder == null || folder.isBlank() ? "imports" : folder),
                            description, null, action);
                }
                case "manage_skill", "manage_mcp" -> {
                    // read/update/remove 用 target(名称或 id);create/register 用 name;
                    // call 展示「服务器.工具名」;tools 展示服务器名(tool 参数时附工具名)
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    String toolArg = node.path("tool").asText("");
                    if (("call".equalsIgnoreCase(action) || "tools".equalsIgnoreCase(action))
                            && target != null && !toolArg.isBlank()) {
                        target = target + "." + toolArg;
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "search_knowledge" -> {
                    // 查询词放 target(折叠行展示「检索: xxx」)
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            node.path("query").asText(null)), description, null, action);
                }
                case "manage_knowledge" -> {
                    // list/remove/reindex 用 target(id);index 用 fileId;list 可用 filter
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("fileId").asText(null);
                    }
                    if (target == null) {
                        target = node.path("filter").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "manage_automation" -> {
                    // toggle/remove/run 用 target(id);create 用 name
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "run_command" -> {
                    // 命令原文放 target 字段(折叠行与审批卡都要完整可见);
                    // cwd 不进 typed input(避免误当"目标"显示)
                    String cmd = node.path("command").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, cmd),
                            description, null, action);
                }
                default -> {
                    if (node.has("action") && node.has("service")) {
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), null),
                                description, node.path("action").asText(null));
                    }
                    if (node.has("service") || node.has("limit")) {
                        Integer limit = node.has("limit") && node.get("limit").isNumber()
                                ? node.get("limit").asInt() : null;
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), limit),
                                description, null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), description, null);
                }
            }
        } catch (Exception e) {
            return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), null, null);
        }
    }

    /** 模型省略 description 参数时的人类可读标题。 */
    private String defaultTitle(String name) {
        return switch (name) {
            case "execute_sql" -> "查询数据库";
            case "execute_write_sql" -> "写入数据库";
            case "read_service_logs" -> "读取服务日志";
            case "manage_container" -> "容器操作";
            case "manage_datasource" -> "数据源管理";
            case "manage_service" -> "服务纳管";
            case "manage_file", "read_file" -> "文件中心";
            case "manage_workspace" -> "工作区文件";
            case "manage_skill" -> "技能管理";
            case "manage_mcp" -> "MCP 服务器管理";
            case "run_command" -> "运行命令";
            case "fetch_media" -> "拉取媒体文件";
            case "search_knowledge" -> "检索知识库";
            case "manage_knowledge" -> "知识库管理";
            case "manage_automation" -> "自动任务管理";
            case "environment_status" -> "环境状态快照";
            default -> name.startsWith("mcp__") ? "调用 MCP 工具" : name;
        };
    }

    /**
     * 工具名归一(验收 F4):兼容别名归到标准名——旧历史/旧调用里的
     * read_file 与 manage_file 视为同一工具,避免新旧名各自计数绕过熔断。
     */
    private static String canonicalToolName(String name) {
        return "read_file".equals(name) ? "manage_file" : name;
    }

    /**
     * 参数规范化(验收 F4):解析为 JSON 对象后按键名排序序列化,
     * 剔除展示字段(description)并归一执行层会接受的别名。
     * 修正两个方向的缺陷:
     * <ul>
     *   <li>漏拦:此前部分工具直接用原始 JSON——description 变化或字段顺序
     *       变化会重置计数,同一调用换描述就能绕过熔断;</li>
     *   <li>误拦:此前 manage_file 只用 target(id)——同一文件的
     *       read/rename/move/delete 指纹相同,不同动作互相阻断。</li>
     * </ul>
     * 解析失败(非法 JSON)时退回原始串——宁可保守也不要漏判。
     */
    private String canonicalArgs(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!(node instanceof ObjectNode obj)) {
                return args;
            }
            // 剔除展示字段(不影响执行语义)
            obj.remove("description");
            // 路径别名归一(与 RiskClassifier.workspacePathOf 同一别名序)
            if (!obj.has("path")) {
                String alias = obj.has("filename") ? obj.path("filename").asText(null)
                        : obj.has("file") ? obj.path("file").asText(null) : null;
                if (alias != null) {
                    obj.put("path", alias);
                }
            }
            obj.remove("filename");
            obj.remove("file");
            // 动作方言归一(与执行层共用)
            if (obj.has("action")) {
                String action = obj.path("action").asText("");
                String canonical = RiskClassifier.normalizeWorkspaceAction(action);
                if (!canonical.equals(action)) {
                    obj.put("action", canonical);
                }
            }
            // 键排序(确定性输出;剔除展示差异后的同一次调用指纹一致)
            java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
            obj.fieldNames().forEachRemaining(f -> sorted.put(f, obj.get(f)));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue().toString());
            }
            return sb.append('}').toString();
        } catch (Exception e) {
            return args;
        }
    }

    /** approval_required 事件载荷:操作类型、目标、参数摘要、风险说明、参数明细。 */
    private ApprovalRequestDto buildApprovalRequest(String stepId, String toolName, ParsedArgs parsed,
                                                    PermissionMode mode, String rawArgs) {
        String actionType;
        String target;
        String risk;
        String detail = null;
        switch (toolName) {
            case "execute_write_sql" -> {
                actionType = "sql_write";
                target = parsed.input().target() != null
                        ? "数据源 " + parsed.input().target()
                        : "数据库(默认连接)";
                detail = "SQL: " + (parsed.input().sql() == null ? "(空)" : parsed.input().sql());
                risk = "将修改真实数据,不可自动撤销";
            }
            case "manage_container" -> {
                actionType = "container_control";
                target = parsed.input().service() == null ? "未知容器" : parsed.input().service();
                detail = "操作: " + parsed.containerAction();
                risk = "停止/重启容器会导致该服务短暂不可用";
            }
            case "manage_datasource" -> {
                actionType = "datasource_manage";
                // 与分类器/执行层同一归一化:别名 add→create / delete→remove 等按目标语义展示明细
                String action = RiskClassifier.normalizeDatasourceAction(parsed.datasourceAction());
                target = parsed.input().target() == null ? "新数据源" : parsed.input().target();
                JsonNode a = parseArgsSafe(rawArgs);
                if ("remove".equals(action)) {
                    detail = "数据源: " + target + "\n后果: 连接记录 + 全部查询历史一并删除";
                    risk = "不可恢复的删除操作";
                } else if ("create".equals(action)) {
                    detail = "名称: " + a.path("name").asText("?")
                            + "\n类型: " + a.path("engine").asText("?")
                            + "\n地址: " + a.path("host").asText("?") + ":" + a.path("port").asInt(0)
                            + "\n数据库: " + a.path("database").asText("?")
                            + "\n用户: " + a.path("username").asText("(空)")
                            + "\n密码: (已隐藏,仅存入数据源服务)";
                    risk = "将新增一个数据库连接";
                } else {
                    risk = "对数据源连接执行 " + action + " 操作";
                }
            }
            case "manage_service" -> {
                actionType = "service_manage";
                // 与分类器/执行层同一归一化(add→register / delete→remove 等)
                String action = RiskClassifier.normalizeServiceAction(parsed.datasourceAction());
                target = parsed.input().target() == null ? "新纳管源" : parsed.input().target();
                JsonNode a = parseArgsSafe(rawArgs);
                if ("register".equals(action)) {
                    String kind = a.path("kind").asText("?");
                    detail = "名称: " + a.path("name").asText("?") + "\n类型: " + kind;
                    if ("FILE".equalsIgnoreCase(kind)) {
                        detail += "\n日志文件: " + a.path("fileLogPath").asText("?");
                    } else if ("DOCKER".equalsIgnoreCase(kind)) {
                        detail += "\n容器: " + a.path("containerName").asText("?");
                    } else {
                        detail += "\n启动命令: " + a.path("command").asText("?")
                                + "\n工作目录: " + a.path("workDir").asText("(无)");
                    }
                    risk = "PROC 源的命令将在宿主机被执行并被持续监控";
                } else if ("remove".equals(action)) {
                    detail = "纳管源: " + target + "(容器/文件本身不受影响)";
                    risk = "删除后该源不再被监控与采集日志";
                } else {
                    risk = "对纳管源执行 " + action + " 操作";
                }
            }
            case "manage_mcp" -> {
                actionType = "mcp_manage";
                // 与分类器/执行层同一归一化:别名 create/delete 等按 register/remove 展示明细
                String action = RiskClassifier.normalizeMcpAction(parsed.datasourceAction());
                JsonNode a = parseArgsSafe(rawArgs);
                if ("register".equals(action)) {
                    // 与执行层同一推断:有 command 无 url → STDIO(模型常省略 transport)
                    // 且与执行层同一方言归一化(http→STREAMABLE 等)
                    String transport = a.path("transport").asText("");
                    if (transport.isBlank() && !a.path("command").asText("").isBlank()
                            && a.path("url").asText("").isBlank()) {
                        transport = "STDIO";
                    }
                    transport = RiskClassifier.normalizeMcpTransport(transport);
                    target = a.path("name").asText("新 MCP 服务器");
                    JsonNode headers = a.path("headers");
                    StringBuilder headerKeys = new StringBuilder();
                    if (headers.isObject()) {
                        headers.fieldNames().forEachRemaining(k ->
                                headerKeys.append(headerKeys.length() > 0 ? ", " : "").append(k));
                    }
                    JsonNode env = a.path("env");
                    StringBuilder envKeys = new StringBuilder();
                    if (env.isObject()) {
                        env.fieldNames().forEachRemaining(k ->
                                envKeys.append(envKeys.length() > 0 ? ", " : "").append(k));
                    }
                    if ("STDIO".equalsIgnoreCase(transport)) {
                        // 本地进程:完整命令行给用户审(装的是什么包一眼可见)
                        StringBuilder cmdline = new StringBuilder(a.path("command").asText("?"));
                        if (a.path("args").isArray()) {
                            for (JsonNode n : a.path("args")) {
                                cmdline.append(' ').append(n.asText(""));
                            }
                        }
                        detail = "本地命令: " + cmdline
                                + (envKeys.length() > 0 ? "\n环境变量: " + envKeys + "(值已隐藏)" : "");
                        risk = "将在本机启动本地进程作为 MCP 服务器(进程有本机权限,其工具会挂载给 Agent 调用)";
                    } else {
                        detail = "名称: " + target
                                + "\n地址: " + a.path("url").asText("?")
                                + "\n传输: " + transport
                                + (headerKeys.length() > 0 ? "\n鉴权头: " + headerKeys + "(值已隐藏)" : "");
                        risk = "将注册外部 MCP 服务器:其工具会挂载给 Agent 调用(外部能力,执行范围未知)";
                    }
                } else if ("remove".equals(action)) {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    detail = "MCP 服务器: " + target + "\n后果: 注册记录与连接一并删除";
                    risk = "删除后其工具立即不可用,需重新注册才能恢复";
                } else if ("call".equals(action)) {
                    // 按名调用外部工具:与 mcp_tool 同语义——服务器+工具名+参数可见
                    target = a.path("target").asText("?");
                    detail = "服务器: " + target + "\n工具: " + a.path("tool").asText("?")
                            + "\n参数: " + Texts.abbreviate(a.path("arguments").toString(), 400);
                    risk = "调用外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                } else if ("setpolicy".equals(action)) {
                    target = a.path("target").asText("?");
                    detail = "服务器: " + target + "\n策略: " + a.path("toolPolicy").asText("?")
                            + (("lazy".equalsIgnoreCase(a.path("toolPolicy").asText("")))
                            ? "\n后果: 其工具不再挂载(改用 tools/call 按需调用,省每轮上下文)"
                            : "\n后果: 其工具重新挂载为独立工具(每轮注入)");
                    risk = "改变 Agent 的工具挂载面";
                } else {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    risk = "对 MCP 服务器执行 " + action + " 操作";
                }
            }
            case "run_command" -> {
                actionType = "terminal_command";
                JsonNode a = parseArgsSafe(rawArgs);
                String cmd = a.path("command").asText("?");
                String cwd = a.path("cwd").asText(null);
                Integer timeout = a.path("timeout").isInt() ? a.path("timeout").asInt() : null;
                String shell = a.path("shell").asText(null);
                // 命令原文完整展示(与 Claude Code 同款防线:用户审的就是将执行的)
                target = Texts.abbreviate(cmd, 60);
                detail = "命令: " + cmd
                        + (cwd != null ? "\n工作目录: " + cwd : "\n工作目录: 工作区根")
                        + (shell != null ? "\nShell: " + shell : "")
                        + (timeout != null ? "\n超时: " + timeout + "s" : "");
                risk = "命令将在本机以当前用户权限执行,可能有文件/网络副作用";
            }
            case "manage_knowledge" -> {
                actionType = "knowledge_manage";
                JsonNode a = parseArgsSafe(rawArgs);
                String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction();
                target = parsed.input().target() == null ? "知识库" : parsed.input().target();
                if ("index".equals(action)) {
                    detail = "文件 id: " + a.path("fileId").asText("?")
                            + (a.path("name").asText("").isBlank() ? "" : "\n展示名: " + a.path("name").asText(""))
                            + "\n后果: 文件内容将被切分+嵌入,加入可检索知识库(可在知识库页删除)";
                    risk = "将把该文件的内容加入知识库索引(可逆:之后可删除文档)";
                } else if ("remove".equals(action)) {
                    detail = "文档 id: " + target + "\n后果: 文档及其全部分块/向量一并删除";
                    risk = "删除后该文档不再可检索,不可自动撤销(文件中心原文件不受影响)";
                } else {
                    risk = "对知识库执行 " + action + " 操作";
                }
            }
            case "manage_automation" -> {
                actionType = "automation_manage";
                JsonNode a = parseArgsSafe(rawArgs);
                String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction();
                target = parsed.input().target() == null ? "新自动任务" : parsed.input().target();
                if ("create".equals(action)) {
                    detail = "规则名: " + a.path("name").asText("?")
                            + "\n触发: " + a.path("triggerType").asText("daily")
                            + "\n无人值守指令: " + a.path("prompt").asText("(空)")
                            + "\n后果: 到点后由无人值守通道自动执行该指令(没有审批门)";
                    risk = "该指令未来将在无人值守通道自动执行——写清楚动作与范围,执行时没有人可以追问";
                } else if ("remove".equals(action)) {
                    detail = "规则 id: " + target + "\n后果: 规则删除,不再触发(执行历史保留)";
                    risk = "删除后该规则不再执行,需重新创建才能恢复";
                } else if ("run".equals(action)) {
                    detail = "规则 id: " + target + "\n后果: 立即执行一次(可能跑数分钟,消耗 LLM 调用)";
                    risk = "立即触发一次自动任务执行";
                } else {
                    risk = "对自动任务执行 " + action + " 操作";
                }
            }
            case "manage_file", "read_file" -> {
                // 文件中心管理动作(2026-09-18 补齐;2026-09-20 更名 manage_file,
                // read_file 兼容别名)的审批明细;list/read 是 LOW 不会走到这
                actionType = "file_manage";
                JsonNode a = parseArgsSafe(rawArgs);
                String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().toLowerCase();
                target = parsed.input().target() == null ? "文件中心" : parsed.input().target();
                switch (action) {
                    case "rename" -> {
                        detail = "文件 id: " + target + "\n新名称: " + a.path("name").asText("?");
                        risk = "将重命名文件中心里的文件";
                    }
                    case "move" -> {
                        detail = "文件 id: " + target + "\n目标文件夹: "
                                + (a.path("folderId").isNumber() ? "id=" + a.path("folderId").asLong() : "根目录");
                        risk = "将移动文件到文件夹";
                    }
                    case "delete" -> {
                        detail = "文件 id: " + target + "\n后果: 移入回收站(可恢复;彻底删除需在文件页操作)";
                        risk = "删除的文件进入回收站,可在文件页恢复";
                    }
                    case "mkdir" -> {
                        detail = "新文件夹: " + a.path("name").asText("?");
                        risk = "将新建一个文件夹";
                    }
                    default -> {
                        detail = "操作: " + action;
                        risk = "对文件中心执行 " + action + " 操作";
                    }
                }
            }
            case "manage_workspace" -> {
                actionType = "workspace_file";
                JsonNode a = parseArgsSafe(rawArgs);
                String action = RiskClassifier.normalizeWorkspaceAction(parsed.datasourceAction());
                // 寻址与风险判定/执行层同一别名序(2026-09-20 统一);list 的 dir 仅作展示兜底
                String p = RiskClassifier.workspacePathOf(a);
                if (p == null || p.isBlank()) {
                    p = a.path("dir").asText(null);
                }
                target = p == null ? "工作区" : p;
                detail = "操作: " + action + " | 路径: " + target
                        + (a.has("content")
                        ? " | 内容预览: " + Texts.abbreviate(a.path("content").asText(""), 200) : "")
                        + (a.has("old_string")
                        ? " | 替换预览: " + Texts.abbreviate(a.path("old_string").asText(""), 120)
                        + " → " + Texts.abbreviate(a.path("new_string").asText(""), 120) : "");
                // 风险文案按真实判定区分(2026-09-19 修复):此前一律硬编码
                // "在工作区之外"——相对路径(区内 LOW 风险)也被这样展示,误导用户;
                // 区内/区外分别给准确描述(move/copy 需看 to 字段)
                String to = a.path("to").asText(null);
                boolean outside = RiskClassifier.isOutsideWorkspace(p)
                        || ("move".equals(action) || "copy".equals(action))
                        && RiskClassifier.isOutsideWorkspace(to);
                risk = outside
                        ? "该路径在工作区之外——将改动本机的真实文件(不可自动撤销)"
                        : "工作区内文件操作(可回退)";
            }
            default -> {
                if (toolName.startsWith("mcp__")) {
                    actionType = "mcp_tool";
                    // 畸形挂载名(LLM 幻觉出 mcp__srv 缺第二个 __)不能让 substring 越界
                    int sep = toolName.indexOf("__", "mcp__".length());
                    String serverName = sep < 0 ? "?" : toolName.substring("mcp__".length(), sep);
                    target = "MCP 服务器 " + serverName;
                    detail = "工具: " + toolName + "\n参数: " + Texts.abbreviate(rawArgs == null ? "{}" : rawArgs, 400);
                    risk = "外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                } else {
                    actionType = toolName;
                    target = parsed.input().service() != null ? parsed.input().service()
                            : (parsed.input().sql() != null ? Texts.abbreviate(parsed.input().sql(), 40) : "—");
                    risk = "该档位下每次工具调用都需要确认";
                }
            }
        }
        return new ApprovalRequestDto(null, stepId, actionType, target,
                defaultTitle(toolName) + " · " + target, risk, detail);
    }

    /** 解析审批明细用的 args JSON;失败返回空节点(明细降级为不含参数)。 */
    private JsonNode parseArgsSafe(String rawArgs) {
        try {
            return objectMapper.readTree(rawArgs == null ? "{}" : rawArgs);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 暴露行数供 UI 折叠预览("21 lines of output")。 */
    private static Integer countLines(String content) {
        if (content == null) return null;
        return content.split("\n", -1).length;
    }

    /**
     * 工具调用日志的 args 脱敏:凭据类字段(headers 的值、password、token 等)
     * 替换为 ***。值只传给服务层,任何日志/步骤/审批明细都不得出现明文。
     */
    /** 单条工具参数重放上限:超过则不附 rawArgs(该步退化为文本历史,防病态超长参数)。 */
    private static final int MAX_REPLAY_ARGS_CHARS = 20_000;


    /** 把脱敏后的原始参数附到 typed input 上(跨轮历史重建用;null/空/非法 = 不附)。 */
    private ChatStepDto.StepInput withRawArgs(ChatStepDto.StepInput input, String args) {
        if (input == null || args == null || args.isBlank()) {
            return input;
        }
        try {
            // 只有合法 JSON 对象才能作为 tool_calls.arguments 重放
            if (!objectMapper.readTree(args).isObject()) {
                return input;
            }
        } catch (Exception e) {
            return input;
        }
        String raw = scrubArgsForLog(args);
        if (raw.length() > MAX_REPLAY_ARGS_CHARS) {
            return input;
        }
        return new ChatStepDto.StepInput(input.sql(), input.service(), input.limit(), input.target(), raw);
    }

    private String scrubArgsForLog(String args) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!(node instanceof ObjectNode obj)) {
                return args;
            }
            JsonNode headers = obj.get("headers");
            if (headers != null && headers.isObject()) {
                ObjectNode masked = objectMapper.createObjectNode();
                headers.fieldNames().forEachRemaining(k -> masked.put(k, "***"));
                obj.set("headers", masked);
            }
            JsonNode env = obj.get("env");
            if (env != null && env.isObject()) {
                ObjectNode masked = objectMapper.createObjectNode();
                env.fieldNames().forEachRemaining(k -> masked.put(k, "***"));
                obj.set("env", masked);
            }
            for (String key : new String[]{"password", "token", "apiKey", "api_key", "secret"}) {
                if (obj.hasNonNull(key)) {
                    obj.put(key, "***");
                }
            }
            return obj.toString();
        } catch (Exception e) {
            return args; // 非法 JSON:原样保留(上游模型参数问题,不含结构化凭据)
        }
    }
}
