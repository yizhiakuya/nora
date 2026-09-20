package com.nora.agent.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.config.LlmProperties;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

/**
 * 对话编排:OpenAI function-calling 风格的 agent 循环:
 * <ol>
 *   <li>RAG 检索(不变)→ 系统提示词引用</li>
 *   <li>工具循环:模型可调工具的轮次;每次调用发一个结构化步骤
 *       (toolName + 解析后的入参 + 带类型的结果),把有界输出回填给模型。
 *       循环跑到模型自己不再调工具为止(对齐 Codex/Claude Code 语义)——
 *       轮数上限只是病态循环保险丝(默认 100),不是任务预算;
 *       上下文增长由压缩控制,用户随时可停。</li>
 *   <li>最终回答逐 token 流式发给 SSE 消费方</li>
 * </ol>
 *
 * <p>Harness 约定(见 docs/harness-tool-calling-research-2026-09-05.md):
 * 工具输出有界(成功 30K 字符 / 失败 10K 头+尾),守卫拒绝按三段式错误形态
 * (什么 + 为什么 + 怎么修),终止步骤状态区分 {@code declined}(规则拒绝)
 * 与 {@code failed}(执行错误),重复相同调用软阻断并给提示,不再执行。
 */
@Service
public class ChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);


    /**
     * 工具轮数保险丝（不是任务预算）。
     *
     * <p>语义对齐 Codex / Claude Code：工具循环跑到**模型自己不再调工具**为止；
     * 上下文增长由压缩（compactForRound）控制，用户随时可停。
     * 轮数上限只用于兜住病态死循环（如模型在两个工具间反复横跳且指纹去重未拦住），
     * 因此给一个远超正常任务需要的大值——实测"扫相册找猫"（50 张照片，
     * 拼图两页 + 48 次逐张细看）自然跑了 17 轮才收尾，旧的 10 轮默认值
     * 会把这类任务拦腰截断。
     */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 100;



    private final LlmProperties llmProperties;
    private final RagRetrievalClient ragRetrievalClient;
    private final SqlToolClient sqlToolClient;
    private final ServiceLogClient serviceLogClient;
    private final ObjectMapper objectMapper;
    /** 出站代理配置(可为 disabled):外网 LLM 上游走代理,内网服务互调直连 */
    private final com.nora.common.http.ProxyProperties proxyProperties;
    private final ModelProviderService modelProviderService;
    private final ApprovalService approvalService;
    private final WriteSqlClient writeSqlClient;
    private final ContainerControlClient containerControlClient;
    private final DataSourceManageClient dataSourceManageClient;
    private final ServiceManageClient serviceManageClient;
    private final FileToolClient fileToolClient;
    private final McpServerService mcpServerService;
    /** Agent 工作区(文件系统上的私有空间;引导文件注入每轮上下文) */
    private final AgentWorkspaceService agentWorkspaceService;
    /** 指令型技能(目录注入 + 按需读全文) */
    private final AgentSkillService agentSkillService;
    /** 本机终端执行(run_command 工具;可空=测试构造器不接) */
    private final TerminalService terminalService;
    /** tools spec 装配器(从本类拆出,2026-09-17 复杂度审计 Step 1)。 */
    private final ChatToolsSpec toolsSpecBuilder;
    /** 工具执行器(从本类拆出,2026-09-17 复杂度审计 Step 2)。 */
    private final ChatToolExecutor toolExecutor;
    /** 模型能力降级注册表(进程内记忆,2026-09-17 拆分 Step 3)。 */
    private final ModelCapabilityRegistry capabilityRegistry;
    /** 工具步骤发射器(从本类拆出,2026-09-17 拆分方案收尾)。 */
    private final ToolStepEmitter stepEmitter;
    /** LLM 上游流式客户端(2026-09-17 拆分 Step 3)。 */
    private final UpstreamLlmClient upstreamClient;
    /** 模型/渠道解析器(2026-09-17 拆分 Step 3)。 */
    private final ModelResolver modelResolver;
    /** 上下文装配器(从本类拆出,2026-09-17 复杂度审计 Step 4)。 */
    private final ChatContextAssembler contextAssembler;
    /** 消息引用解析与注入(📎/📄/@ 按钮;2026-09-17)。 */
    private final MessageRefResolver messageRefResolver;
    /** 画廊列表预取(可为 null:测试场景)。 */
    private final GalleryPrefetcher galleryPrefetcher;
    /** 轮次取消信号(可为 null:测试场景);工具循环用它做不可吞的取消检查。 */
    private final TurnCancellation turnCancellation;
    private final int maxToolRounds;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    ModelProviderService modelProviderService,
                                    ApprovalService approvalService,
                                    WriteSqlClient writeSqlClient,
                                    ContainerControlClient containerControlClient,
                                    DataSourceManageClient dataSourceManageClient,
                                    ServiceManageClient serviceManageClient,
                                    FileToolClient fileToolClient,
                                    McpServerService mcpServerService,
                                    AgentWorkspaceService agentWorkspaceService,
                                    AgentSkillService agentSkillService,
                                    TerminalService terminalService,
                                    @org.springframework.beans.factory.annotation.Value("${nora.agent.max-tool-rounds:100}") int maxToolRounds,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    com.nora.common.http.ProxyProperties proxyProperties,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    GalleryPrefetcher galleryPrefetcher,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    MediaFetchService mediaFetchService,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    RelayMediaRouter relayMediaRouter,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    TurnCancellation turnCancellation,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    KnowledgeManageClient knowledgeManageClient,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    AutomationManageClient automationManageClient,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    EnvironmentStatusClient environmentStatusClient,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    AppSettingStore appSettingStore) {
        this.llmProperties = llmProperties;
        this.ragRetrievalClient = ragRetrievalClient;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.objectMapper = objectMapper;
        this.modelProviderService = modelProviderService;
        this.approvalService = approvalService;
        this.writeSqlClient = writeSqlClient;
        this.containerControlClient = containerControlClient;
        this.dataSourceManageClient = dataSourceManageClient;
        this.serviceManageClient = serviceManageClient;
        this.fileToolClient = fileToolClient;
        this.mcpServerService = mcpServerService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.terminalService = terminalService;
        this.toolsSpecBuilder = new ChatToolsSpec(objectMapper, agentWorkspaceService, agentSkillService,
                mcpServerService, terminalService, mediaFetchService,
                knowledgeManageClient, automationManageClient, environmentStatusClient);
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
        this.galleryPrefetcher = galleryPrefetcher;
        this.toolExecutor = new ChatToolExecutor(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient,
                containerControlClient, dataSourceManageClient, serviceManageClient, fileToolClient,
                mcpServerService, terminalService, agentWorkspaceService, agentSkillService, galleryPrefetcher,
                mediaFetchService, relayMediaRouter, knowledgeManageClient, automationManageClient,
                environmentStatusClient);
        this.capabilityRegistry = new ModelCapabilityRegistry();
        this.stepEmitter = new ToolStepEmitter(objectMapper, approvalService, toolExecutor,
                capabilityRegistry, turnCancellation);
        this.turnCancellation = turnCancellation;
        this.upstreamClient = new UpstreamLlmClient(objectMapper, capabilityRegistry);
        this.modelResolver = new ModelResolver(llmProperties, modelProviderService);
        this.contextAssembler = new ChatContextAssembler(objectMapper, agentWorkspaceService,
                agentSkillService, toolsSpecBuilder, dataSourceManageClient, serviceLogClient, appSettingStore);
        this.messageRefResolver = new MessageRefResolver(fileToolClient, ragRetrievalClient, agentSkillService,
                mcpServerService);
    }

    /** 兼容构造(2026-09-18 前测试用):新工具客户端为 null = 不挂载。 */
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    ModelProviderService modelProviderService,
                                    ApprovalService approvalService,
                                    WriteSqlClient writeSqlClient,
                                    ContainerControlClient containerControlClient,
                                    DataSourceManageClient dataSourceManageClient,
                                    ServiceManageClient serviceManageClient,
                                    FileToolClient fileToolClient,
                                    McpServerService mcpServerService,
                                    AgentWorkspaceService agentWorkspaceService,
                                    AgentSkillService agentSkillService,
                                    TerminalService terminalService,
                                    int maxToolRounds,
                                    com.nora.common.http.ProxyProperties proxyProperties,
                                    GalleryPrefetcher galleryPrefetcher,
                                    MediaFetchService mediaFetchService,
                                    RelayMediaRouter relayMediaRouter,
                                    TurnCancellation turnCancellation) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper,
                modelProviderService, approvalService, writeSqlClient, containerControlClient,
                dataSourceManageClient, serviceManageClient, fileToolClient, mcpServerService,
                agentWorkspaceService, agentSkillService, terminalService, maxToolRounds, proxyProperties,
                galleryPrefetcher, mediaFetchService, relayMediaRouter, turnCancellation,
                null, null, null, null);
    }

    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, null, DEFAULT_MAX_TOOL_ROUNDS, null, null, null, null, null,
                null, null, null, null);
    }

    /** 测试入口:显式最大工具轮数,无 provider store。 */
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    int maxToolRounds) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, null, maxToolRounds, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * 运行一轮对话:检索 → 工具循环 → 流式回答。
     *
     * @param userMessage   用户消息文本
     * @param history       该会话的历史轮次(旧到新)
     * @param eventConsumer 实时接收 step/delta/sources 事件
     * @return 流结束时以完整回答文本完成的 future
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, List.of(), null, eventConsumer);
    }

    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, null, eventConsumer);
    }

    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, null, eventConsumer);
    }

    /**
     * @param requestedReasoningLevel 对话框选择的思考等级;null = 用该模型在设置页配置的默认等级
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                PermissionMode.ASSIST, eventConsumer);
    }

    /**
     * @param permissionMode 审批档位(ASK 每次询问 / ASSIST 高风险询问 / FULL 全放行)
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, null, eventConsumer);
    }

    /**
     * @param sessionId 会话 ID(审批请求绑定用;null = 不启用审批门,全部自动执行)
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, sessionId, null, eventConsumer);
    }

    /**
     * @param sessionId 会话 ID(审批请求绑定用;null = 不启用审批门,全部自动执行)
     * @param providerId 前端选定的模型服务商(渠道)id;同名模型跨渠道时精确定位,null = 按模型名解析
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            Long providerId,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, sessionId, providerId, null, eventConsumer);
    }

    /**
     * @param taskContext 结构化任务上下文(M2-01,可空);与正文旧引用行合并,
     *                    结构化优先、旧格式回退
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            Long providerId,
                                            com.nora.agent.dto.TaskContext taskContext,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, sessionId, providerId, taskContext, false, eventConsumer);
    }

    /**
     * @param unattended 无人值守(定时任务):CRITICAL 工具**直接拒绝**、不等待
     *                   交互审批(2026-09-20 定时任务=往会话发消息;用户确认的
     *                   权限取舍见实施记录 §7.4——有会话但现场无人)
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            Long providerId,
                                            com.nora.agent.dto.TaskContext taskContext,
                                            boolean unattended,
                                            ChatEventConsumer eventConsumer) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (userMessage.length() > 8000) {
            throw new IllegalArgumentException("message exceeds 8000 characters");
        }
        if (!configured(requestedModel, providerId)) {
            eventConsumer.step(new ChatStepDto("s-config", "think", "模型未配置",
                    "请先在设置中心配置 LLM API Key 和端点", 0L, "failed"));
            String message = "当前尚未配置可用的模型。请到设置中心配置 LLM 服务商、端点和 API Key 后重试。";
            eventConsumer.delta(message);
            return CompletableFuture.completedFuture(new ChatTurn(message, List.of(), null, null, null, null));
        }
        // 请求级等级优先,其次设置页为该模型配置的默认等级(在 resolveLlm 内合并)
        // 第 1 步:知识检索(尽力而为,在 LLM 调用之前)
        long retrievalStart = System.currentTimeMillis();
        List<CitationDto> citations = ragRetrievalClient.search(userMessage, 6);
        // 消息引用(📎/📄/@ 按钮)注入:真实内容排在语义检索命中之前——
        // 「用户明确引用的内容」优先级高于「检索到的相关片段」,且不受
        // 检索分数下限影响(引用是确定性输入,不是相似度猜测)。
        // M2-01:结构化 context 与正文旧引用行合并解析(结构化优先,旧格式回退)。
        List<MessageRefResolver.Ref> messageRefs = MessageRefResolver.parse(userMessage);
        List<CitationDto> refCitations = messageRefResolver.resolveContext(taskContext, messageRefs);
        int refCount = taskContext != null && taskContext.refs() != null
                ? Math.max(taskContext.refs().size(), messageRefs.size())
                : messageRefs.size();
        if (!refCitations.isEmpty()) {
            List<CitationDto> merged = new java.util.ArrayList<>(refCitations);
            merged.addAll(citations);
            citations = merged;
        }
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        if (!citations.isEmpty()) {
            String summary = refCitations.isEmpty()
                    ? "召回 " + citations.size() + " 个相关片段（" + citations.get(0).docName() + " 等）"
                    : "引用 " + refCount + " 项 + 召回 "
                            + (citations.size() - refCitations.size()) + " 个相关片段";
            eventConsumer.step(new ChatStepDto(
                    "s-rag", "tool", "检索知识库", summary,
                    retrievalMs, "completed", null, null, null, 0));
            eventConsumer.sources(citations);
        }

        // 第 2 步:工具循环——每轮都是真实的流式请求。
        // content/reasoning 增量从上游到达即逐 token 转发给客户端;
        // tool_call 参数碎片在本地累积,流结束后执行工具。
        // 先解析一次拿到合并后的思考等级(请求级 > 设置页该模型默认),工具轮与最终回答共用
        ResolvedLlm resolved = modelResolver.resolve(requestedModel, requestedReasoningLevel, providerId);
        if (resolved == null) {
            // configured() 已校验过,这里防御性兜底
            throw new IllegalStateException("no LLM provider resolved for model: " + requestedModel);
        }
        // 系统性上下文预算(设计见 docs/context-management-design.md):
        // 会话级裁剪 + 轮内微压缩 + 超限恢复,统一走 ContextBudget
        ContextBudget budget = new ContextBudget(resolved.contextWindow());
        ChatContextAssembler.SystemPromptResult[] promptOut = new ChatContextAssembler.SystemPromptResult[1];
        List<WireMessage> messages = contextAssembler.buildMessages(userMessage, history, citations, reflections, budget, promptOut);
        // 注入可见性(dsh 模式):把本轮注入的记忆/技能清单作为步骤下发,
        // 前端时间线可展开查看真实注入内容;无注入时静默跳过。
        // 注入本身每轮都发生(系统提示必须随每次请求携带),但步骤仅在首轮或
        // 内容较上次下发有变化时下发——不变的轮次不再重复展示(降噪,2026-09-12)
        emitContextSteps(promptOut[0], history, eventConsumer);
        final String reasoningLevel = resolved.effectiveReasoningLevel();
        String toolOutcome = null;
        final int[] roundsUsed = {0};
        final int[] lastPromptEstimate = {0};
        // TTFT:首个模型 token 到达耗时(对齐 harness TurnComplete 的 time_to_first_token_ms)
        final long turnStartMs = System.currentTimeMillis();
        final long[] ttftMs = {-1};
        TokenUsage totalUsage = null;
        // 循环检测状态(结果感知:同参且同结果才累计;设计 §9.3)
        LoopDetector loopDetector = new LoopDetector();
        try {
            for (int round = 0; round < maxToolRounds; round++) {
                if (isTurnCancelled(sessionId)) {
                    // 工具执行/审批等待期间被取消:不再发起新的上游请求,按取消收场。
                    // 取消信号 = 线程中断 OR TurnCancellation 会话标志——后者不可被
                    // 下游(JDBC/SSE)消费,是「停止生成」的可靠判据(实测修复:此前
                    // 中断标志被消费后,取消轮继续跑了 4 个工具轮)。
                    log.info("tool loop interrupted (user cancel) before round {}, abort turn", round + 1);
                    return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                }
                roundsUsed[0] = round + 1;
                long overhead = contextAssembler.requestOverheadTokens();
                int compactedCount = contextAssembler.compactForRound(messages, budget, false, overhead);
                if (compactedCount > 0 || contextAssembler.lastRecycledImages() > 0) {
                    StringBuilder what = new StringBuilder();
                    if (compactedCount > 0) what.append("已压缩 ").append(compactedCount).append(" 条早期工具结果");
                    if (contextAssembler.lastRecycledImages() > 0) {
                        if (what.length() > 0) what.append("、");
                        what.append("已回收 ").append(contextAssembler.lastRecycledImages()).append(" 条消息的早期图片附件");
                    }
                    eventConsumer.step(new ChatStepDto("s-compact-" + round, "think",
                            "整理上下文", what + (compactedCount > 0 ? ",释放约 " + contextAssembler.estimateTokensFreed() + " tokens 预算" : ""),
                            0L, "completed", null, null, null, round));
                }
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                        + (int) overhead;
                StreamTurnResult result = streamTurn(messages, requestedModel, reasoningLevel,
                        round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                if (result.usage() != null) {
                    totalUsage = totalUsage == null ? result.usage() : totalUsage.add(result.usage());
                }
                if (result.failed()) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 取消:中断被上游阻塞读转成 failed 结果(streamUpstream 已恢复标志),
                        // 这里短路——绝不当「空响应」重试,否则取消变成多烧一整轮 token
                        log.info("tool round interrupted (user cancel), abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    StreamTurnResult retry = null;
                    boolean explicitVisionReject = ModelCapabilityRegistry.isVisionUnsupported(result.errorMessage());
                    boolean maybeVisionReject = !explicitVisionReject
                            && hasImagesInMessages(messages) && ModelCapabilityRegistry.isGenericUpstream400(result.errorMessage());
                    if (explicitVisionReject || maybeVisionReject) {
                        // 模型不支持识图(上游拒绝图片):剥离图片后重试。
                        // 中转流式下的 400 只有模糊文案(maybeVisionReject),因此以“剥图后重试是否成功”
                        // 作为最终判据:成功才记住结论,后续轮次直接不再附图(“不支持就不去看”)。
                        int stripped = stripImagesFromMessages(messages);
                        log.info("vision retry for {} ({}): stripped {} image(s), explicit={}",
                                resolved.model(), resolved.baseUrl(), stripped, explicitVisionReject);
                        StreamTurnResult visionRetry = streamTurn(messages, requestedModel, reasoningLevel,
                                round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!visionRetry.failed()) {
                            // 确认:这个模型确实看不了图(下一次直接不发图片)
                            capabilityRegistry.markVisionRejected(resolved);
                            eventConsumer.step(new ChatStepDto("s-vision-" + round, "think", "模型不支持识图",
                                    "已自动跳过图片内容继续回答（可在设置中切换支持识图的模型）",
                                    0L, "completed", null, null, null, round + 1));
                        }
                        retry = visionRetry;
                    } else if (ChatContextAssembler.isContextOverflow(result.errorMessage())) {
                        // 上下文超限:硬压缩到恢复线(窗口 60%)后重试一次
                        contextAssembler.compactForRound(messages, budget, true, overhead);
                        lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                                + (int) overhead;
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                    } else if (ModelCapabilityRegistry.isReasoningEffortUnsupported(result.errorMessage())
                            && reasoningLevel != null && !reasoningLevel.isBlank()) {
                        // 上游不认这个档位(gemini-3.5-flash 等报 invalid_reasoning_effort):
                        // 剥掉档位重试一次——思考等级是增强项,不该让整轮对话失败。
                        // 重试期间同时挂上「该档位被拒」与「该模型不注入档位」两条结论:
                        // 后者让重试真正不带 reasoning_effort(传 null 档位时 gpt-5 家族
                        // 会回落注入 medium,只挂前者重试就不是"剥掉");
                        // 重试成功才保留结论(后续同档位请求不再撞 400,且换成别的档位
                        // 仍会正常尝试注入);失败则撤销(瞬时错误不拉黑)。
                        capabilityRegistry.markEffortRejected(resolved);
                        capabilityRegistry.markEffortStripped(resolved);
                        log.info("reasoning_effort rejected by {} ({}), retrying without it",
                                resolved.model(), resolved.baseUrl());
                        retry = streamTurn(messages, requestedModel, null, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!retry.failed()) {
                            eventConsumer.step(new ChatStepDto("s-effort-" + round, "think", "模型不支持该思考等级",
                                    "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                                    0L, "completed", null, null, null, round + 1));
                        } else {
                            capabilityRegistry.clearEffortRejected(resolved);
                            capabilityRegistry.clearEffortStripped(resolved);
                        }
                    } else if (result.content().isEmpty() && result.toolCalls().isEmpty()) {
                        // 中转渠道偶发 4xx/5xx(无内容返回):自动重试一次,重试仍失败才放弃本轮。
                        // 已有部分内容/工具调用的失败不重试(内容已随流转发,重发会重复输出)
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                    }
                    if (retry != null) {
                        if (retry.usage() != null) {
                            totalUsage = totalUsage == null ? retry.usage() : totalUsage.add(retry.usage());
                        }
                        if (!retry.failed()) {
                            result = retry;
                        }
                    }
                }
                if (result.failed()) {
                    if (isTurnCancelled(sessionId)) {
                        // 流中取消(已转发部分内容):不发 s-error step,直接按取消收场
                        log.info("tool round interrupted (user cancel) with partial content, abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    eventConsumer.step(new ChatStepDto("s-error", "think", "模型返回空响应",
                            result.errorMessage() != null ? result.errorMessage() : "上游未返回内容,请重试",
                            null, "failed", null, null, null, round + 1));
                    break;
                }
                if (result.toolCalls().isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    // 校准用**当轮** usage:传累计值会与单轮估算相除产生虚高比值
                    contextAssembler.logCalibration(lastPromptEstimate[0], result.usage(), budget);
                    return CompletableFuture.completedFuture(new ChatTurn(result.content(), citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
                }
                messages.add(new WireMessage(result.assistantMessage()));
                for (JsonNode call : result.toolCalls()) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    loopDetector.countAttempt();
                    String toolStepId = "s-call-" + loopDetector.attempts() + "-" + callId;
                    stepEmitter.emitToolStep(toolStepId, name, args, loopDetector, messages, callId,
                            round + 1, permissionMode, sessionId, unattended, resolved, eventConsumer);
                    String lastResult = contextAssembler.lastToolResult(messages);
                    if (lastResult != null) {
                        toolOutcome = lastResult;
                    }
                }
            }
        } catch (Exception e) {
            // 取消穿透工具循环(审批等待 join 被中断抛 CompletionException(InterruptedException)
            // 且消费掉标志;或流内异常带中断 cause):不回落强制回答,直接按取消收场
            if (Texts.isInterruption(e) || isTurnCancelled(sessionId)) {
                Thread.currentThread().interrupt();
                log.info("tool loop interrupted (user cancel), abort turn");
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
            }
            log.warn("tool loop failed, falling back to plain answer", e);
        }
        if (isTurnCancelled(sessionId)) {
            // s-error break / 轮次自然耗尽后取消:同样不发最终回答请求
            log.info("turn interrupted (user cancel) before final answer, abort turn");
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
        }

        // 第 3 步:强制回答的流式轮次(工具结果已在消息里)
        long answerStart = System.currentTimeMillis();
        final String fallbackOutcome = toolOutcome;

        final int reasoningRound = roundsUsed[0] + 1;
        long answerOverhead = contextAssembler.requestOverheadTokens();
        lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                + (int) answerOverhead;
        StreamTurnResult finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
        // 瞬时上游错误(空内容失败)自动重试一次;超限先硬压缩再重试。
        // 线程被中断(用户取消)绝不重试——那会让取消多烧一整轮上游 token
        if (finalResult.failed() && finalResult.content().isBlank() && !isTurnCancelled(sessionId)) {
            if (ChatContextAssembler.isContextOverflow(finalResult.errorMessage())) {
                contextAssembler.compactForRound(messages, budget, true, answerOverhead);
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                        + (int) answerOverhead;
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            } else if (ModelCapabilityRegistry.isReasoningEffortUnsupported(finalResult.errorMessage())
                    && reasoningLevel != null && !reasoningLevel.isBlank()) {
                // 工具循环在首个上游请求前异常退出(catch 兜底走强制回答)时,这一轮
                // 是本轮第一个上游请求:坏档位要在这里也能降级,否则整轮失败且每轮
                // 复现。与工具轮同款:挂两条结论(档位被拒 + 该模型不注入档位)让重试
                // 真正不带 reasoning_effort,重试成功才保留。
                capabilityRegistry.markEffortRejected(resolved);
                capabilityRegistry.markEffortStripped(resolved);
                log.info("reasoning_effort rejected on final answer for {} ({}), retrying without it",
                        resolved.model(), resolved.baseUrl());
                finalResult = streamFinalAnswer(messages, requestedModel, null,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
                if (!finalResult.failed()) {
                    eventConsumer.step(new ChatStepDto("s-effort-final", "think", "模型不支持该思考等级",
                            "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                            0L, "completed", null, null, null, reasoningRound));
                } else {
                    capabilityRegistry.clearEffortRejected(resolved);
                    capabilityRegistry.clearEffortStripped(resolved);
                }
            } else {
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            }
        }
        if (!finalResult.failed()) {
            String answerText = finalResult.content();
            if (finalResult.usage() != null) {
                totalUsage = totalUsage == null ? finalResult.usage() : totalUsage.add(finalResult.usage());
            }
            if (!answerText.isBlank()) {
                contextAssembler.logCalibration(lastPromptEstimate[0], finalResult.usage(), budget);
                return CompletableFuture.completedFuture(new ChatTurn(answerText, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
            }
        }
        if (isTurnCancelled(sessionId)) {
            // 取消:不再用最后一次工具结果兜底(那会把取消轮标成正常完成、
            // 落库完整内容,与前端「停止生成」的半截气泡错位)
            log.info("turn interrupted (user cancel) at final fallback, abort turn");
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
        }
        // 流式最终回答失败:回落到最后一次工具结果作为回答,而不是死流
        String fallback = fallbackOutcome != null && !fallbackOutcome.isBlank()
                ? "查询结果：\n```\n" + Texts.abbreviate(fallbackOutcome, ChatToolExecutor.MAX_FAILURE_CHARS) + "\n```"
                : "";
        if (!fallback.isBlank()) {
            eventConsumer.delta(fallback);
            return CompletableFuture.completedFuture(new ChatTurn(fallback, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
        }
        eventConsumer.step(new ChatStepDto("s-error", "think", "模型调用失败",
                finalResult.errorMessage(), System.currentTimeMillis() - answerStart, "failed",
                null, null, null, null));
        return CompletableFuture.failedFuture(
                new IllegalStateException(finalResult.errorMessage() != null ? finalResult.errorMessage() : "LLM stream failed"));
    }

    /**
     * 本轮是否已被用户取消:线程中断 OR 会话级取消标志。
     *
     * <p>为什么不能只看线程中断(2026-09-17 实测 bug):用户在 fetch_media
     * 大批量下载中途点「停止生成」,中断标志在下载返回后的步骤落库/SSE
     * 链路上被下游(JDBC/连接池)意外消费——工具循环下一轮再查已为 false,
     * 整轮在用户已停止后继续跑了 4 个工具轮。TurnCancellation 的标志
     * 只由本服务读写,不可被消费,是可靠判据。
     */
    private boolean isTurnCancelled(String sessionId) {
        return Thread.currentThread().isInterrupted()
                || (turnCancellation != null && sessionId != null && turnCancellation.isCancelled(sessionId));
    }















    /**
     * 请求里是否携带图片附件(多模态 tool 消息)。
     */
    private static boolean hasImagesInMessages(List<WireMessage> messages) {
        for (WireMessage m : messages) {
            JsonNode content = m.node().path("content");
            if (!content.isArray()) continue;
            for (JsonNode part : content) {
                if ("image_url".equals(part.path("type").asText())) return true;
            }
        }
        return false;
    }


    /** 从 messages 里剥离所有图片附件,返回被剥离的张数。 */
    private int stripImagesFromMessages(List<WireMessage> messages) {
        int stripped = 0;
        for (WireMessage m : messages) {
            ObjectNode n = m.node();
            if (!"tool".equals(n.path("role").asText())) continue;
            JsonNode content = n.path("content");
            if (!content.isArray()) continue;
            StringBuilder text = new StringBuilder();
            int imgs = 0;
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText("")).append("\n");
                } else if ("image_url".equals(part.path("type").asText())) {
                    imgs++;
                }
            }
            if (imgs == 0) continue;
            String body = text.toString().stripTrailing();
            n.put("content", body + "\n(注:本次工具返回了 " + imgs
                    + " 张图片,但当前模型不支持图像输入,图片内容未提供)");
            stripped += imgs;
        }
        return stripped;
    }






    /** 是否配置了 LLM API key。 */
    public boolean configured() {
        return configured(null);
    }

    /**
     * 会话标题生成用的系统提示。
     *
     * <p>要点：限制长度（侧栏一行放得下）、强制单行、禁止包装（模型爱回
     * 「标题：xxx」或加引号）、禁止"关于…的讨论"这类无信息量的套话。
     */
    private static final String TITLE_SYSTEM_PROMPT = """
            你为对话生成一个简短标题。规则：
            1. 只输出标题本身，不要引号、不要「标题：」前缀、不要任何解释；
            2. 长度不超过 12 个汉字（或 24 个英文字符），必须单行；
            3. 用与用户消息相同的语言；
            4. 直接概括用户在做什么/问什么，不要写「关于…的讨论」「用户询问」这类套话；
            5. 不要以句号、问号等标点结尾。
            """;

    /** 送模型用于起标题的用户消息上限（字符）：标题只需开头意图，无需全文。 */
    private static final int TITLE_EXCERPT_CHARS = 600;

    /** 落库标题的最大长度：兜住模型不听话时的超长输出（DB 列宽 255）。 */
    private static final int TITLE_MAX_CHARS = 40;

    /**
     * 用一次轻量 LLM 调用为会话生成短标题（同步阻塞；调用方放到线程池里跑）。
     *
     * <p>复用主链路的上游调用（{@link #streamUpstream}），因此协议分歧
     * （openai / responses）、出站代理、供应商额外请求头都与对话完全一致——
     * 不另起一套 HTTP 逻辑。
     *
     * @return 清理后的标题；未配置模型或上游失败返回 null（调用方保留占位标题）
     */
    public String generateSessionTitle(String userMessage, String requestedModel) {
        return generateSessionTitle(userMessage, requestedModel, null);
    }

    /**
     * @param providerId 渠道 id(与对话同一解析口径,同名模型跨渠道时标题也走用户所选渠道)
     */
    public String generateSessionTitle(String userMessage, String requestedModel, Long providerId) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        // 标题是纯转写任务，不需要推理：显式压到最低档省钱省时
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), "none");
        if (llm == null) {
            return null;
        }
        try {
            ObjectNode body = upstreamClient.baseBody(true, llm);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", TITLE_SYSTEM_PROMPT);
            String excerpt = userMessage.length() <= TITLE_EXCERPT_CHARS
                    ? userMessage
                    : userMessage.substring(0, TITLE_EXCERPT_CHARS);
            messages.addObject().put("role", "user").put("content", excerpt);
            // 不带 tools：标题任务不允许调用工具
            StreamTurnResult result = upstreamClient.streamUpstream(llm, body, (content, reasoning) -> {
            });
            if (result.failed()) {
                log.warn("session title generation failed: {}", result.errorMessage());
                return null;
            }
            return cleanTitle(result.content());
        } catch (Exception e) {
            log.warn("session title generation error: {}", e.toString());
            return null;
        }
    }

    /**
     * 清理模型输出的标题：折叠空白、剥掉包裹符号与「标题：」前缀、限长。
     *
     * <p>实测模型不会严格遵守格式：会给引号、写「标题：xxx」、甚至在标题后
     * 另起一行解释。这里统一清洗，避免脏标题直接进侧栏。
     */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\s+", " ").trim();
        // 只取第一行（模型可能补一段说明）
        if (t.isEmpty()) {
            return null;
        }
        // 垃圾拦截(2026-09-21 实测):起标题的模型偶尔把工具调用标记当正文输出
        // (DeepSeek 的 <｜｜DSML｜｜ invoke ...> 实测泄漏进侧栏标题),
        // 含这类标记的输出不是标题——返回 null 保留占位标题,别把垃圾写进侧栏
        if (t.contains("DSML") || t.contains("｜") || t.contains("invoke name")) {
            return null;
        }
        // 剥前缀：标题：/ 会话标题：/ title:
        t = t.replaceAll("(?i)^(会话)?(标题|title)\\s*[:：]\\s*", "");
        // 剥两端包裹：引号、书名号、方括号、星号（markdown 粗体）
        t = t.replaceAll("^[\"'“”‘’《》\\[\\]【】*]+", "").replaceAll("[\"'“”‘’《》\\[\\]【】*]+$", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= TITLE_MAX_CHARS ? t : t.substring(0, TITLE_MAX_CHARS) + "…";
    }

    public boolean configured(String requestedModel) {
        return modelResolver.resolve(requestedModel) != null;
    }

    /** Variant honouring an explicit provider id (渠道精确解析)。 */
    public boolean configured(String requestedModel, Long providerId) {
        return modelResolver.resolve(requestedModel, null, providerId) != null;
    }

    /** 一次流式轮次的 provider token 统计(中继省略 usage 时字段为 null)。 */
    public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

        TokenUsage add(TokenUsage other) {
            if (other == null) return this;
            return new TokenUsage(
                    plus(inputTokens, other.inputTokens),
                    plus(outputTokens, other.outputTokens),
                    plus(totalTokens, other.totalTokens));
        }

        private static Integer plus(Integer a, Integer b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + b;
        }
    }


    private StreamTurnResult streamTurn(List<WireMessage> messages, String requestedModel,
                                        String reasoningLevel, int round,
                                        ChatEventConsumer eventConsumer,
                                        long[] ttftMs, long turnStartMs) {
        return streamTurn(messages, requestedModel, reasoningLevel, round, eventConsumer, ttftMs, turnStartMs, null);
    }

    private StreamTurnResult streamTurn(List<WireMessage> messages, String requestedModel,
                                        String reasoningLevel, int round,
                                        ChatEventConsumer eventConsumer,
                                        long[] ttftMs, long turnStartMs, Long providerId) {
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = upstreamClient.baseBody(true, llm);
        body.set("messages", upstreamClient.messagesArray(messages));
        body.set("tools", toolsSpec());
        // 请求即将发出:刷新消费方的推理计时锚点(RAG/装配时间不计入思考时长)
        eventConsumer.upstreamRequestStarted();
        return upstreamClient.streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (ttftMs[0] < 0 && (contentToken != null || reasoningToken != null)) {
                ttftMs[0] = System.currentTimeMillis() - turnStartMs;
            }
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }

    /** 最终轮次(tool_choice=none)的强制回答变体(见 {@link #streamTurn})。 */
    private StreamTurnResult streamFinalAnswer(List<WireMessage> messages, String requestedModel,
                                               String reasoningLevel, int round,
                                               ChatEventConsumer eventConsumer,
                                               long[] ttftMs, long turnStartMs) {
        return streamFinalAnswer(messages, requestedModel, reasoningLevel, round, eventConsumer, ttftMs, turnStartMs, null);
    }

    private StreamTurnResult streamFinalAnswer(List<WireMessage> messages, String requestedModel,
                                               String reasoningLevel, int round,
                                               ChatEventConsumer eventConsumer,
                                               long[] ttftMs, long turnStartMs, Long providerId) {
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = upstreamClient.baseBody(true, llm);
        body.set("messages", upstreamClient.messagesArray(messages));
        body.set("tools", toolsSpec());
        // 最终轮:模型必须回答,不能再调工具
        body.put("tool_choice", "none");
        eventConsumer.upstreamRequestStarted();
        return upstreamClient.streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (ttftMs[0] < 0 && (contentToken != null || reasoningToken != null)) {
                ttftMs[0] = System.currentTimeMillis() - turnStartMs;
            }
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }





    /** chat.completions 请求体的 messages 数组 → WireMessage 列表(responses 转换辅助)。 */


    /**
     * 按 provider 附加的 HTTP 头。OpenCode 免费档会拒绝没有 X-Session-ID 的
     * 请求("free tier can only be used in OpenCode");ID 由 API key 派生,
     * 跨轮稳定(provider 侧会话连续性)且不泄露任何信息。
     */
    /** DEBUG 报文日志:协议/模型/档位/URL/请求体(api_key 不入日志,Authorization 头不拼进 body)。 */

    /**
     * Provider 专属附加头(opencode 需要 X-Session-ID)。
     * 空数组时必须跳过 {@code HttpRequest.Builder.headers(String...)}:
     * JDK 对 0 个参数同样抛 IAE "wrong number, 0, of parameters"(varargs
     * 成对校验不接受空数组),曾在所有普通 provider 上导致每轮请求必失败。
     */





    /** OpenAI reasoning_effort 合法值(截至今日上游公开档位)。 */

    /**
     * 是否支持/需要 reasoning_effort 透传的模型家族:
     * OpenAI(gpt-5/o)、Anthropic thinking(claude-*-thinking,实测 2026-09-05:
     * 不带 reasoning_effort 时中转站不返回 reasoning_content)、
     * 中转站档位后缀命名(gemini-*-high 等)。
     */

    /** 读取 OpenAI 兼容中继发出的常见 reasoning 字段。 */



    /** tools spec 装配委托(实现见 ChatToolsSpec,2026-09-17 拆分)。 */
    private ArrayNode toolsSpec() {
        return toolsSpecBuilder.build();
    }













    /*
     * 注:不再单列"协议封装"常量。实测(空会话单请求)76K body → in=11550,
     * 其中消息体仅 ~2K 字符——即 74K 的 tools 段实际承担了 ~11K tokens,
     * 已含协议封装/系统字段。再叠加一个固定常量会重复计算(实测 ratio 0.61)。
     */











    /**
     * 下发「注入上下文」步骤(dsh 模式:注入内容对用户可见,自描述 form 声明信息形态)。
     * 工作区引导 → form=instructions;技能目录 → form=catalog。无注入时静默跳过。
     *
     * <p>降噪(2026-09-12):注入本身每轮都发生(系统提示必须随每次请求携带),
     * 但步骤只在<b>首轮或内容较上次下发有变化</b>时下发——与历史里最近一次同名步骤
     * 逐字段对比,一致则跳过(不变的重复展示只是噪音)。内容变化(如 agent 自演化
     * 编辑了记忆文件)仍会重新下发,保证「模型这轮读到的记忆变过」对用户可见。
     */
    private void emitContextSteps(ChatContextAssembler.SystemPromptResult prompt, List<ChatStoreService.StoredMessage> history,
                                  ChatEventConsumer eventConsumer) {
        ChatStepDto.ContextInfo prevMemory = lastEmittedContext(history, "s-context-memory");
        ChatStepDto.ContextInfo prevSkills = lastEmittedContext(history, "s-context-skills");
        if (prompt.workspace() != null && !prompt.workspace().files().isEmpty()) {
            List<ChatStepDto.ContextFile> files = prompt.workspace().files().stream()
                    .map(f -> new ChatStepDto.ContextFile(f.path(), f.bytes(), f.truncated(), f.missing(), f.content()))
                    .toList();
            boolean unchanged = prevMemory != null
                    && java.util.Objects.equals(files, prevMemory.files())
                    && java.util.Objects.equals(prompt.workspace().dailyNotes(), prevMemory.dailyNotes());
            if (!unchanged) {
                int injected = prompt.workspace().files().stream()
                        .filter(f -> !f.missing() && !f.truncated())
                        .toList().size();
                String detail = "注入 " + injected + " 个文件"
                        + (prompt.workspace().dailyNotes().isEmpty()
                                ? ""
                                : " + " + prompt.workspace().dailyNotes().size() + " 篇日记清单");
                eventConsumer.step(new ChatStepDto("s-context-memory", "context", "加载长期记忆", detail,
                        0L, "completed", null, null, null, 0,
                        new ChatStepDto.ContextInfo("instructions", "workspace-bootstrap",
                                files, null, prompt.workspace().dailyNotes())));
            }
        }
        if (prompt.skills() != null && !prompt.skills().entries().isEmpty()) {
            List<ChatStepDto.ContextEntry> entries = prompt.skills().entries().stream()
                    .map(e -> new ChatStepDto.ContextEntry(e.name(), e.description(), e.category()))
                    .toList();
            boolean unchanged = prevSkills != null && java.util.Objects.equals(entries, prevSkills.entries());
            if (!unchanged) {
                eventConsumer.step(new ChatStepDto("s-context-skills", "context", "加载技能目录",
                        "启用 " + entries.size() + " 个技能（正文按需读取）",
                        0L, "completed", null, null, null, 0,
                        new ChatStepDto.ContextInfo("catalog", "skill-catalog", null, entries, null)));
            }
        }
    }

    /** 会话历史里最近一次已下发的注入步骤(倒序;用于「内容不变不重复展示」判定)。 */
    private static ChatStepDto.ContextInfo lastEmittedContext(List<ChatStoreService.StoredMessage> history,
                                                              String stepId) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            List<ChatStepDto> steps = history.get(i).steps();
            if (steps == null) {
                continue;
            }
            for (ChatStepDto s : steps) {
                if (stepId.equals(s.id()) && s.context() != null) {
                    return s.context();
                }
            }
        }
        return null;
    }








    /** 一轮对话的 SSE 事件回调。 */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        default void reasoningDelta(Integer roundIndex, String token) {
        }

        /** 即将向上游发出一次模型请求:消费方用它刷新推理计时锚点(排除 RAG/装配耗时)。 */
        default void upstreamRequestStarted() {
        }

        default void approvalRequired(ApprovalRequestDto request) {
        }

        void sources(List<CitationDto> citations);
    }

    /**
     * Result of one chat turn. {@code promptTokens} 是最后一次请求的 token
     * 估算、{@code contextWindow} 是生效窗口——两者随 done 下发给前端进度条。
     */
    public record ChatTurn(String answer, List<CitationDto> citations, TokenUsage usage,
                           Long contextWindow, Integer promptTokens, Long ttftMs) {
    }

    /** 线上格式消息包装(用 JsonNode 以混入 tool 消息)。 */
}
