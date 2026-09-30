package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

/**
 * 上下文装配器(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 4):
 * 系统提示装配(SYSTEM_PROMPT + 工作区/技能注入 + RAG 片段)、历史装配
 * (工具链 wire 重建)、轮内微压缩/旧图回收、token 估算与校准日志。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class ChatContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ChatContextAssembler.class);

    private final ObjectMapper objectMapper;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSkillService agentSkillService;
    /** tools spec 提供者(toolsOverheadTokens 动态估算用)。 */
    private final ChatToolsSpec toolsSpecBuilder;
    /** 环境摘要注入用(可为 null:测试场景)。 */
    private final DataSourceManageClient dataSourceManageClient;
    private final ServiceLogClient serviceLogClient;
    /** 用户偏好读取(M2-05;可为 null = 测试场景,不注入)。 */
    private final AppSettingStore appSettingStore;

    /** 最近一次 compactForRound 回收的旧图张数(可视化 step 用)。 */
    private int lastRecycledImages;

    /** 最近一次 compactForRound 释放的 token 估算(可视化 step 用)。 */
    private long estimateTokensFreed;

    ChatContextAssembler(ObjectMapper objectMapper,
                         AgentWorkspaceService agentWorkspaceService,
                         AgentSkillService agentSkillService,
                         ChatToolsSpec toolsSpecBuilder) {
        this(objectMapper, agentWorkspaceService, agentSkillService, toolsSpecBuilder, null, null);
    }

    ChatContextAssembler(ObjectMapper objectMapper,
                         AgentWorkspaceService agentWorkspaceService,
                         AgentSkillService agentSkillService,
                         ChatToolsSpec toolsSpecBuilder,
                         DataSourceManageClient dataSourceManageClient,
                         ServiceLogClient serviceLogClient) {
        this(objectMapper, agentWorkspaceService, agentSkillService, toolsSpecBuilder,
                dataSourceManageClient, serviceLogClient, null);
    }

    ChatContextAssembler(ObjectMapper objectMapper,
                         AgentWorkspaceService agentWorkspaceService,
                         AgentSkillService agentSkillService,
                         ChatToolsSpec toolsSpecBuilder,
                         DataSourceManageClient dataSourceManageClient,
                         ServiceLogClient serviceLogClient,
                         AppSettingStore appSettingStore) {
        this.objectMapper = objectMapper;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.toolsSpecBuilder = toolsSpecBuilder;
        this.dataSourceManageClient = dataSourceManageClient;
        this.serviceLogClient = serviceLogClient;
        this.appSettingStore = appSettingStore;
    }

    int lastRecycledImages() {
        return lastRecycledImages;
    }

    long estimateTokensFreed() {
        return estimateTokensFreed;
    }

    /**
     * 基础系统提示(harness 协议层,设计对齐 Hermes 的 stable 层哲学):
     * 只放跨任务、不可协商的协议约定;人格/风格/任务习惯全部下放到工作区文件
     * (SOUL.md 人格、AGENTS.md 约定、USER.md/MEMORY.md 记忆快照)——它们是 agent
     * 的可演化指令,编辑文件即改变后续行为,无需改代码。
     */
    private static final String SYSTEM_PROMPT = """
            你是 Nora 个人工作台中的 AI 助手。以下为协议约定(优先级最高,始终遵守):
            1. 引用知识库来源时必须使用 [[docName]] 标记(渲染协议)。
            2. 需要真实数据或执行操作时,直接调用相应工具——不要凭记忆编造,不要虚构工具执行结果或报错,也不要只描述计划而不行动;工具返回错误时如实说明。
            3. 你的语气、风格与工作习惯定义在下方工作区的 SOUL.md 与 AGENTS.md 中——它们是
               你的可演化指令:self-evolution 是预期行为,想调整行为方式时直接编辑对应文件。
            4. 用户说「记住…」时必须写入工作区文件落盘,不能只口头答应。
            5. 最终回答使用自然的 Markdown:段落紧凑、结论前置;不要复述工具参数或执行过程。
            6. 你是这个工作台的操作员:它包含对话/知识库/数据源/环境控制台/文件/自动任务/
               MCP/技能/设置九个功能面,你通过工具直接操作它们(查库、读日志、启停容器、
               跑命令、管数据源/技能/MCP)。用户问「你能做什么」或需要了解工作台功能时,
               先读技能「工作台使用手册」(manage_skill action=read)获取权威说明,不要凭记忆
               编造功能;不确定工作台某能力是否存在时,用工具查证而不是猜测。
            7. 工具可能返回图片附件(如相册图片)。当图片以图像附件形式提供时,
               直接基于你看到的内容回答;若当前模型不支持图像输入(图片会被跳过并附说明),
               如实告知“当前模型看不到图片”并建议切换到支持识图的模型,
               绝不要凭文件名或描述猜测图片内容。
            8. 文件交付:报告、代码、表格等成果先用工具保存真实文件，再调用 open_file。
               targets 仅接受 workspace:相对路径、file:id、media:缓存键；intent=deliver 会登记工作区成果，
               intent=inspect 用于查看已有文件。不要把报告正文包装进 nora-artifacts 的 text/files 画廊，
               也不要声称虚构路径已保存。open_file 只展示文件，模型需读取内容时另用 manage_file read。
               最终回答简要说明结论和文件入口即可。此规则替代旧工作区/技能中的文件画廊交付要求。
               相册缩略图和表格、差异、时间线等业务卡片继续按「产物画廊指南」的协议使用；它们的数据必须来自真实工具结果。
            9. 早期助手回复和工具过程可能已省略,用户消息仍按原顺序保留。
               后续补充不会自动取消之前的限制;明确修改、撤销或切换任务时以较新的要求为准,
               不要重新执行已撤销的旧任务。
            10. 请求指代不明或缺少关键信息时(「那个 MCP」「那个文件」无法确定所指、
               不知道用哪个方案),先调 ask_user 工具向用户提问(有候选项就给 options),
               等回答后再动手——不要靠翻文件系统/数据库/日志来"猜"用户指哪个。
               ask_user 会暂停本轮、用户作答后从答案继续,问一句的成本远低于猜十步。
            11. 探索有边界:查找信息优先在工作台自己的范围内(工作区文件、文件中心、
               知识库、工作台数据库)。不要扫描用户主目录、其他工具(Claude Code 等)的
               配置与历史会话日志、SSH 到其他服务器翻找——除非用户明确要求。
               若任务需要的密钥/凭据不在工作台内,用 ask_user 让用户提供或存入
               设置页「环境变量」,而不是四处翻找或尝试绕过脱敏。
            """;

    /**
     * 系统性上下文装配(设计见 docs/context-management-design.md):
     * 固定层之外先为全部历史用户消息留出上下文,剩余空间装入最近的助手/
     * 工具过程(最多 40 条历史)。不以最后一条消息或摘录替代累计用户要求。
     */
    List<WireMessage> buildMessages(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<CitationDto> citations,
                                            List<String> reflections,
                                            ContextBudget budget,
                                            SystemPromptResult[] promptOut) {
        List<WireMessage> messages = new ArrayList<>();
        SystemPromptResult prompt = systemPromptWith(citations);
        promptOut[0] = prompt;
        String systemPrompt = prompt.text();
        messages.add(WireMessage.system(objectMapper, systemPrompt));
        String reflectionBlock = reflections == null || reflections.isEmpty() ? null
                : "此前类似任务的失败反思（仅作参考）：\n- " + String.join("\n- ", reflections);
        if (reflectionBlock != null) {
            messages.add(WireMessage.system(objectMapper, reflectionBlock));
        }

        int fixedCost = ContextBudget.estimateTokens(systemPrompt)
                + (reflectionBlock == null ? 0 : ContextBudget.estimateTokens(reflectionBlock))
                + ContextBudget.estimateTokens(userMessage)
                + (int) requestOverheadTokens();
        long historyBudget = budget.historyBudgetTokens(fixedCost);
        // controller 在调用前已把当前 user 消息落库(loadMessages 的末条就是它),
        // 这里只拼历史部分并排除末条,末尾统一 add(userMessage)——否则当前消息
        // 会被发两遍(中转/上游按两条独立 user 消息计费并处理)
        int historyEnd = history.size();
        if (historyEnd > 0) {
            ChatStoreService.StoredMessage last = history.get(historyEnd - 1);
            if ("user".equals(last.role()) && userMessage.equals(last.content())) {
                historyEnd--;
            }
        }
        long used = 0;
        for (int i = 0; i < historyEnd; i++) {
            if ("user".equals(history.get(i).role())) {
                used += wireCostOf(history.get(i));
            }
        }
        if (used > historyBudget) {
            throw new IllegalArgumentException("历史用户要求已超出当前模型可容纳的上下文，无法完整保留目标与限制。"
                    + "请切换更大上下文的模型，或在新会话中提供当前有效的任务要求后继续。");
        }
        int from = historyEnd;
        while (from > 0 && historyEnd - from < 40) {
            ChatStoreService.StoredMessage prev = history.get(from - 1);
            int cost = "user".equals(prev.role()) ? 0 : wireCostOf(prev);
            if (used + cost > historyBudget) break;
            used += cost;
            from--;
        }
        // 保留被裁过程中的用户原文及先后顺序,包括修订和撤销;不提升为 system 指令。
        for (int i = 0; i < from; i++) {
            if ("user".equals(history.get(i).role())) {
                appendHistoryMessage(messages, history.get(i));
            }
        }
        for (int i = from; i < historyEnd; i++) {
            appendHistoryMessage(messages, history.get(i));
        }
        messages.add(WireMessage.user(objectMapper, userMessage));
        return messages;
    }

    /**
     * 把一条持久化历史消息按 wire 结构重建进请求(对齐 Claude Code/Codex 的
     * 「工具链即历史」):assistant 消息若带可重建的工具步骤(有 toolName +
     * 脱敏原始参数 + 终态结果),按 roundIndex 逐轮重放为
     * assistant(tool_calls)+ tool(result) 对,最后附回答文本——模型看到
     * 自己真实的调用记录与「调用→结果」节奏,而不是被剥掉结构的纯文本
     * 总结(实测:缺失该结构时弱模型会续写编造工具结果)。
     * 旧数据(无 rawArgs)优雅退化为纯文本,不产生孤儿 tool 消息。
     */
    void appendHistoryMessage(List<WireMessage> messages, ChatStoreService.StoredMessage msg) {
        if ("user".equals(msg.role())) {
            messages.add(WireMessage.user(objectMapper, msg.content()));
            return;
        }
        if (!"assistant".equals(msg.role())) {
            return;
        }
        String content = msg.content() == null ? "" : msg.content();
        List<ChatStepDto> toolSteps = rebuildableToolSteps(msg.steps());
        if (toolSteps.isEmpty()) {
            if (!content.isBlank()) {
                messages.add(WireMessage.assistant(objectMapper, content));
            }
            return;
        }
        // 按轮次分组:同一轮的多工具调用是一批(assistant 一条 + 多个 tool 结果)
        java.util.LinkedHashMap<Integer, List<ChatStepDto>> byRound = new java.util.LinkedHashMap<>();
        for (ChatStepDto step : toolSteps) {
            byRound.computeIfAbsent(step.roundIndex(), k -> new ArrayList<>()).add(step);
        }
        for (List<ChatStepDto> roundSteps : byRound.values()) {
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", "");
            ArrayNode calls = assistant.putArray("tool_calls");
            for (ChatStepDto step : roundSteps) {
                ObjectNode call = calls.addObject();
                call.put("id", step.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", step.toolName());
                fn.put("arguments", step.input().rawArgs());
            }
            messages.add(new WireMessage(assistant));
            for (ChatStepDto step : roundSteps) {
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", step.id());
                toolMsg.put("content", toolResultText(step));
                messages.add(new WireMessage(toolMsg));
            }
        }
        if (!content.isBlank()) {
            messages.add(WireMessage.assistant(objectMapper, content));
        }
    }

    /** 可重建为 wire tool_call 的步骤:工具名 + 脱敏原始参数齐备(结果缺省也有占位)。 */
    private static List<ChatStepDto> rebuildableToolSteps(List<ChatStepDto> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(s -> "tool".equals(s.type())
                        && s.toolName() != null && !s.toolName().isBlank()
                        && s.input() != null && s.input().rawArgs() != null && !s.input().rawArgs().isBlank())
                .toList();
    }

    /** 重放给模型的工具结果文本:成功用 content;declined/失败无 content 时用 error(与轮内回填同语义)。 */
    private static String toolResultText(ChatStepDto step) {
        ChatStepDto.StepResult result = step.result();
        if (result == null) {
            return "(no output)";
        }
        if (result.content() != null) {
            return result.content();
        }
        if (result.error() != null) {
            return "ERROR: " + result.error();
        }
        return "(no output)";
    }

    /** 一条历史消息重建后的 wire token 估算(含工具链;预算循环用,与 messageTokens 同口径)。 */
    static int wireCostOf(ChatStoreService.StoredMessage msg) {
        if ("user".equals(msg.role())) {
            return ContextBudget.estimateTokens(msg.content()) + 8;
        }
        if (!"assistant".equals(msg.role())) {
            return 8;
        }
        int cost = ContextBudget.estimateTokens(msg.content() == null ? "" : msg.content()) + 8;
        for (ChatStepDto step : rebuildableToolSteps(msg.steps())) {
            cost += ContextBudget.estimateTokens(step.toolName())
                    + ContextBudget.estimateTokens(step.input().rawArgs())
                    + ContextBudget.estimateTokens(toolResultText(step)) + 24;
        }
        return cost;
    }

    /** 轮内微压缩永不触碰的尾部消息数(最近一轮工具往返 + 余量)。 */
    private static final int COMPACTION_TAIL_KEEP = 6;

    /**
     * 保留图片附件的最近工具批次数(含当前批次;更早批次的图片在下一轮请求前回收)。
     *
     * <p>为什么按"轮"而不是"条":实测(2026-09-13)按条保留 2 条时,模型在
     * 第 3 轮请求 4 张图后,第 4 轮(最终回答)前这 4 张里较早的 2 张已被回收——
     * 模型尚未把结论写进文本,只能如实报告"看不到画面"。按轮保留 2 个批次
     * 覆盖"当前批次 + 上一批次",让模型有完整的窗口消化刚看过的图。
     * 模型看过的更早的图通常已消化成结论;需要回看时可按编号清单里的 id
     * 重新取图,比让每轮请求体无限膨胀(每张 data URL ~600KB)更划算。
     */
    private static final int COMPACTION_KEEP_RECENT_IMAGE_ROUNDS = 2;

    /**
     * 每次请求的固定 overhead token 估算下限(tools spec + 协议封装/系统字段)。
     *
     * <p>实测(2026-09-13):启用 MCP 后 tools spec 已膨胀到 75 个工具/74KB
     * (≈2 万 tokens)——固定 1800 严重低估,导致 promptEstimate 4051 vs
     * 真实 11565(ratio 2.85),压缩触发偏晚。改为按 toolsSpec() 实际序列化
     * 长度动态估算(见 {@link #toolsOverheadTokens()}),此常量降级为下限
     * (无 tools 的路径/序列化失败时兜底)。
     */
    static final int PER_REQUEST_OVERHEAD_TOKENS = 1_800;

    /** tools spec 序列化缓存的长度(按工具面版本失效:MCP 注册/刷新/启停后重算)。 */
    private volatile int toolsSpecTokensCache = -1;
    /** 缓存对应的工具面版本(见 {@link ChatToolsSpec#toolsRevision()})。 */
    private volatile long toolsSpecRevisionCache = -1;

    /**
     * tools spec 的 token 估算(按实际序列化长度)。
     *
     * <p>口径与消息体不同:实测(2026-09-13,空会话单请求)body 77174 字符
     * → 上游上报 in=11395;扣掉消息体估算(≈2.4K)后 tools 段 73792 字符
     * ≈ 9K tokens,即 **≈7-8 字符/token**——JSON 键名/语法高度重复,
     * tokenizer 打包效率远高于普通文本。若沿用消息体的 CJK 感知估算
     * (≈20K)会过估 2 倍,导致压缩过早触发(实测 ratio 0.47)。
     * 取 /7 略偏保守(宁可略早压缩)。工具集按版本缓存——MCP 变更后
     * 重算,否则运行期注册服务器后估算偏小、压缩触发偏晚(2026-09-18 修复)。
     */
    int toolsOverheadTokens() {
        long revision = toolsSpecBuilder.toolsRevision();
        int cached = toolsSpecTokensCache;
        if (cached >= 0 && toolsSpecRevisionCache == revision) return cached;
        int tokens;
        try {
            String json = objectMapper.writeValueAsString(toolsSpecBuilder.build());
            tokens = Math.max(PER_REQUEST_OVERHEAD_TOKENS, json.length() / TOOLS_SPEC_CHARS_PER_TOKEN);
        } catch (Exception e) {
            log.warn("tools spec serialize failed, fallback to constant overhead: {}", e.toString());
            tokens = PER_REQUEST_OVERHEAD_TOKENS;
        }
        toolsSpecTokensCache = tokens;
        toolsSpecRevisionCache = revision;
        return tokens;
    }

    /** tools spec 的字符/token 经验值(实测 7-8;取 7 略保守)。 */
    private static final int TOOLS_SPEC_CHARS_PER_TOKEN = 7;

    /** 本轮请求的真实固定开销 = tools spec(动态) + 协议封装余量。 */
    long requestOverheadTokens() {
        return toolsOverheadTokens();
    }

    /**
     * 轮内微压缩(microcompact,语义对齐 Claude Code):预估 prompt 超过
     * 触发线时,把最旧的工具结果就地改写为头尾摘录。只改 content,role/
     * tool_call_id 不动(OpenAI tool_call 配对校验不破),最近
     * {@link #COMPACTION_TAIL_KEEP} 条消息完整保留;历史轮的失败也只留要点。
     * force=true(上游已报超限)时压缩目标降为恢复线(窗口 60%)。
     */
    int compactForRound(List<WireMessage> messages, ContextBudget budget, boolean force,
                                long overheadTokens) {
        // 旧图回收:与 token 预算无关的带宽护栏——每轮都执行(见 recycleOldImages)
        int recycledImages = recycleOldImages(messages, force);
        lastRecycledImages = recycledImages;
        long total = 0;
        for (WireMessage m : messages) {
            total += messageTokens(m);
        }
        total += overheadTokens; // tools spec + 协议封装,占请求大头
        long limit = force
                ? (long) (budget.window() * ContextBudget.RECOVERY_RATIO)
                : budget.triggerTokens() - budget.outputReserve();
        if (total <= limit) {
            estimateTokensFreed = 0;
            return 0;
        }
        int compactedCount = 0;
        long freed = 0;
        int lastCompactable = messages.size() - COMPACTION_TAIL_KEEP;
        for (int i = 1; i < lastCompactable && total > limit; i++) { // i=0 system 不动
            WireMessage m = messages.get(i);
            if (!"tool".equals(m.node().path("role").asText())) continue;
            JsonNode contentNode = m.node().path("content");
            if (contentNode.isArray()) {
                // 多模态工具结果(带图):只压缩文本部分(图片回收在 recycleOldImages)
                for (JsonNode part : contentNode) {
                    if (!"text".equals(part.path("type").asText())) continue;
                    String t = part.path("text").asText("");
                    String compacted = compactToolContent(t);
                    if (compacted.equals(t)) continue;
                    freed += ContextBudget.estimateTokens(t) - ContextBudget.estimateTokens(compacted);
                    total -= ContextBudget.estimateTokens(t) - ContextBudget.estimateTokens(compacted);
                    compactedCount++;
                    ((ObjectNode) part).put("text", compacted);
                }
                continue;
            }
            String content = contentNode.asText("");
            String compacted = compactToolContent(content);
            if (compacted.equals(content)) continue; // 太短不值得
            freed += ContextBudget.estimateTokens(content) - ContextBudget.estimateTokens(compacted);
            total -= ContextBudget.estimateTokens(content) - ContextBudget.estimateTokens(compacted);
            compactedCount++;
            ((ObjectNode) m.node()).put("content", compacted);
        }
        estimateTokensFreed = freed;
        return compactedCount;
    }

    /**
     * 旧图回收:只保留最近 {@link #COMPACTION_KEEP_RECENT_IMAGE_ROUNDS} 个
     * 工具批次的图片附件,更早批次剥离(保留文本与编号清单);尾部
     * {@link #COMPACTION_TAIL_KEEP} 条消息永不触碰。
     *
     * <p>动机:图片 token 计费极低(实测本环境中转几乎不计——每轮追加 4-5 张
     * 全尺寸照片仅使上报输入 +~640 tokens),但每张 data URL 有 ~600KB——
     * 48 张图的找猫任务若全部保留,单请求体将达 ~100MB,上传耗时主导每轮时长。
     * 回收把在途图片稳定压在小窗口内,模型需要回看时可按编号清单重新取图。
     *
     * @return 被剥离图片附件的消息条数
     */
    int recycleOldImages(List<WireMessage> messages, boolean force) {
        int keepBatches = force ? 1 : COMPACTION_KEEP_RECENT_IMAGE_ROUNDS;
        int lastCompactable = messages.size() - COMPACTION_TAIL_KEEP;
        int batchIndex = 0; // 0 = 当前批次(倒序走到的第一个批次)
        int stripped = 0;
        for (int i = messages.size() - 1; i >= 1; i--) {
            JsonNode node = messages.get(i).node();
            JsonNode calls = node.path("tool_calls");
            if ("assistant".equals(node.path("role").asText()) && calls.isArray() && !calls.isEmpty()) {
                batchIndex++; // 越过该批次的 tool_calls 锚点,再往前即更早批次
                continue;
            }
            JsonNode contentNode = node.path("content");
            if (!contentNode.isArray()) continue;
            boolean hasImg = false;
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) {
                    hasImg = true;
                    break;
                }
            }
            if (!hasImg) continue;
            if (batchIndex < keepBatches || i >= lastCompactable) continue;
            ArrayNode kept = objectMapper.createArrayNode();
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) continue;
                kept.add(part);
            }
            kept.add(objectMapper.createObjectNode()
                    .put("type", "text")
                    .put("text", "(早期图片已省略——如需回看,重新调用对应工具或按编号清单中的 id 单独取图)"));
            ((ObjectNode) messages.get(i).node()).set("content", kept);
            stripped++;
        }
        return stripped;
    }

    /** 单条工具结果的压缩形态:头 800 + 尾 200,失败只留头 400(错误要点在前)。 */
    static String compactToolContent(String content) {
        boolean failure = content.startsWith("ERROR:");
        int head = failure ? 400 : 800;
        int tail = failure ? 0 : 200;
        if (content.length() <= head + tail + 80) return content;
        String body = content.substring(0, head)
                + "\n…[早期工具结果已压缩,原文 " + content.length() + " 字符]…";
        if (tail > 0) {
            body += "\n" + content.substring(content.length() - tail);
        }
        return body;
    }

    /** 一条 wire 消息的 token 估算(content + tool_calls 参数 + 封装)。 */
    static int messageTokens(WireMessage m) {
        ObjectNode n = m.node();
        JsonNode contentNode = n.path("content");
        int tokens = ContextBudget.estimateTokens(textOfContent(contentNode) == null ? "" : textOfContent(contentNode))
                + 8;
        if (contentNode.isArray()) {
            // 图片按单张固定成本估算(见 IMAGE_TOKEN_ESTIMATE):与 data URL 长度
            // 无关——实测追加 ~14MB 图片仅使上游上报输入 +639 tokens;此前
            // "按 ~16 字符/token"的推断来自把累计 usage 与单轮估算相比的
            // 日志口径错误(见 logCalibration),并非真实计费方式。
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) {
                    tokens += IMAGE_TOKEN_ESTIMATE;
                }
            }
        }
        JsonNode calls = n.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode c : calls) {
                tokens += ContextBudget.estimateTokens(c.path("function").path("name").asText(""))
                        + ContextBudget.estimateTokens(c.path("function").path("arguments").asText("")) + 8;
            }
        }
        return tokens;
    }

    /**
     * 估算校准日志:把**当前轮**的服务端 prompt 估算与该轮上游真实 inputTokens
     * 对齐输出(真实值含 tools spec/协议封装 overhead,估算只含消息体)。
     * 比值持续偏离预期时调整估算系数——观测驱动的闭环。
     *
     * <p>必须传**当轮** usage:曾误传累计 usage 与单轮估算相除,产生
     * ratio=10.73 的虚高告警,并误导出"图片按 data URL 长度计费"的错误结论
     * (实测:追加 14MB 图片仅使上游上报输入 +639 tokens)。
     */
    void logCalibration(int promptEstimate, ChatOrchestrationService.TokenUsage usage, ContextBudget budget) {
        if (usage == null || usage.inputTokens() == null || promptEstimate <= 0) return;
        int real = usage.inputTokens();
        double ratio = (double) real / promptEstimate;
        // 只在明显偏离时告警(±35% 外),正常波动打 debug
        String r = String.format("%.2f", ratio);
        if (ratio < 0.65 || ratio > 1.35) {
            log.warn("context estimate calibration: promptEstimate={} realInput={} ratio={} window={} trigger={}",
                    promptEstimate, real, r, budget.window(), budget.triggerTokens());
        } else {
            log.debug("context estimate ok: promptEstimate={} realInput={} ratio={}",
                    promptEstimate, real, r);
        }
    }

    /** 上游上下文超限错误识别(各家中转措辞不一,宽松匹配)。 */
    static boolean isContextOverflow(String errorMessage) {
        if (errorMessage == null) return false;
        String s = errorMessage.toLowerCase();
        return s.contains("context length") || s.contains("maximum context")
                || s.contains("context_length") || s.contains("too many tokens")
                || s.contains("token limit") || s.contains("上下文长度");
    }

    /**
     * 单张图片的 token 估算。
     *
     * <p>实测(2026-09-13,同一会话逐轮对比):从 0 图到 1 图 in 增加 ~1.0K
     * (其中含少量工具结果文本);1 图到 3 图再增加 ~2.3K,即单张 ≈1K。
     * 与 data URL 长度无关(视觉分辨率口径)——74KB 的 tools 段计 ~11K tokens,
     * 而单张 2-3MB 的图片只计 ~1K。取 1K 与实测对齐;图片字节的膨胀
     * 由 recycleOldImages 单独兜底(那才是带宽问题,不是 token 问题)。
     *
     * <p>历史注:旧值 1_200 → 一度按 data URL 长度/16 估算(误读校准日志口径,
     * 见 logCalibration 注释)→ 3_000(过估,实测 ratio 0.61)。现取 1_000。
     */
    private static final int IMAGE_TOKEN_ESTIMATE = 1_000;

    /**
     * 系统提示装配:基础 prompt + 工作区引导(AGENTS/SOUL/USER/MEMORY)+ 技能目录 + RAG 片段。
     * 工作区/目录注入是 best-effort:不可用时静默跳过,不阻断对话。
     *
     * <p>返回文本与注入元数据(工作区文件清单/日记清单/技能条目):调用方据此下发
     * 「注入上下文」步骤,前端可展开查看真实注入内容(dsh 的注入可见性模式)。
     */
    SystemPromptResult systemPromptWith(List<CitationDto> citations) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        // 当前时间:必须注入——模型训练知识过期,不注入就只能 run_command Get-Date
        // 现挖(实测:每轮"看昨天拍的视频"都要先跑一条 PowerShell 查日期)
        sb.append("\n当前时间:").append(currentTimeLine()).append("。\n");
        AgentWorkspaceService.BootstrapResult workspaceBootstrap = null;
        AgentSkillService.CatalogBundle skillCatalog = null;
        // 全局记忆:跨会话事实/偏好,约束每轮回答
        if (agentWorkspaceService != null) {
            try {
                workspaceBootstrap = agentWorkspaceService.bootstrap();
                if (workspaceBootstrap != null && !workspaceBootstrap.text().isBlank()) {
                    sb.append('\n').append(workspaceBootstrap.text());
                }
            } catch (Exception e) {
                log.warn("workspace inject failed (ignored): {}", e.getMessage());
            }
        }
        // 技能目录:列出启用技能的名称/描述,正文由 manage_skill action=read 按需拉取
        if (agentSkillService != null) {
            try {
                skillCatalog = agentSkillService.catalogBundle();
                if (skillCatalog != null && !skillCatalog.text().isBlank()) {
                    sb.append('\n').append(skillCatalog.text());
                }
            } catch (Exception e) {
                log.warn("skill catalog inject failed (ignored): {}", e.getMessage());
            }
        }
        // 环境摘要(2026-09-18 复盘数据驱动):数据源名单 + 纳管源名单——
        // 消灭「先 list→schema→再查」的探索调用(实测 execute_sql 20.6% 失败
        // 全是猜库名/schema 前缀,read_service_logs 40% 失败全是猜容器名)。
        // TTL 缓存:名单变化不频繁,避免每轮打下游服务。
        String envSummary = environmentSummary();
        if (envSummary != null && !envSummary.isBlank()) {
            sb.append('\n').append(envSummary);
        }
        // 用户结构化偏好(M2-05,方案 §9):报告语言/默认成果目录/命名习惯。
        // 优先级:当前用户明确要求 > 本次任务配置 > 本偏好 > 工作区记忆中的通用偏好。
        String prefs = userPreferencesSummary();
        if (prefs != null && !prefs.isBlank()) {
            sb.append('\n').append(prefs);
        }
        if (!citations.isEmpty()) {
            sb.append("\n以下是知识库检索到的相关片段：\n");
            for (CitationDto c : citations) {
                // 2026-09-22(阶段 A):注入**完整块正文**(evidence),不再用
                // 500 字符截断的 snippet——答案在块后半段时此前「命中却无法回答」。
                // 单块证据上限 4K 字符(防单个超大块挤占上下文预算);截断时
                // 附可操作提示,让模型知道内容不完整。
                String body = c.evidence();
                if (body != null && body.length() > EVIDENCE_CHARS) {
                    body = body.substring(0, EVIDENCE_CHARS)
                            + "\n…[该片段已截断,共 " + c.evidence().length() + " 字符]";
                }
                sb.append("[[").append(c.docName()).append("#chunk").append(c.chunkIndex())
                        .append("]] ").append(body).append('\n');
            }
        }
        return new SystemPromptResult(sb.toString(), workspaceBootstrap, skillCatalog);
    }

    /** 单块注入证据上限(字符):完整块正文的封顶,防超大块挤占预算。 */
    private static final int EVIDENCE_CHARS = 4_000;

    /** 系统提示文本 + 注入元数据(下发「注入上下文」步骤用;null = 该来源未注入)。 */
    record SystemPromptResult(String text,
                                      AgentWorkspaceService.BootstrapResult workspace,
                                      AgentSkillService.CatalogBundle skills) {
    }

    // ---------- 环境摘要注入(2026-09-18 复盘数据驱动) ----------

    /** 环境摘要缓存(名单变化不频繁;TTL 90s,避免每轮打下游服务)。 */
    private volatile String envSummaryCache;
    private volatile long envSummaryCacheAt;

    /**
     * 用户结构化偏好摘要(M2-05):报告语言/默认成果目录/命名习惯——
     * 三项都是可执行设置(进入实际运行上下文),不是只改 UI 的摆设。
     * best-effort:读取失败返回 null(不阻断对话)。
     */
    String userPreferencesSummary() {
        if (appSettingStore == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> prefs = appSettingStore.raw("user-preferences");
            if (prefs == null || prefs.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("用户偏好(优先级低于用户本次的明确要求):");
            Object lang = prefs.get("reportLanguage");
            if (lang != null && !String.valueOf(lang).isBlank()) {
                sb.append("\n- 报告与成果默认使用语言:").append(lang);
            }
            Object dir = prefs.get("defaultOutputDir");
            if (dir != null && !String.valueOf(dir).isBlank()) {
                sb.append("\n- 默认成果目录:工作区 ").append(dir).append("(保存报告/导出文件时优先放这里)");
            }
            Object naming = prefs.get("namingStyle");
            if (naming != null && !String.valueOf(naming).isBlank()) {
                sb.append("\n- 文件命名习惯:").append(naming);
            }
            return sb.length() > "用户偏好(优先级低于用户本次的明确要求):".length() ? sb.toString() : null;
        } catch (Exception e) {
            log.debug("user preferences inject failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 环境摘要:数据源名单(名称+引擎+库)与纳管源名单(名称)。
     * 注入后模型直接用正确名字写 SQL / 读日志,不再猜。
     * best-effort:两个来源都失败返回 null。
     */
    String environmentSummary() {
        if (dataSourceManageClient == null && serviceLogClient == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String cached = envSummaryCache;
        if (cached != null && now - envSummaryCacheAt < 90_000) {
            return cached;
        }
        StringBuilder sb = new StringBuilder();
        if (dataSourceManageClient != null) {
            try {
                String ds = dataSourceManageClient.nameSummary();
                if (ds != null && !ds.isBlank()) {
                    sb.append("可用数据源(execute_sql 的 datasource 参数用这里的名字):\n")
                            .append(ds).append('\n');
                }
            } catch (Exception e) {
                log.debug("datasource summary failed (ignored): {}", e.getMessage());
            }
        }
        if (serviceLogClient != null) {
            try {
                List<String> services = serviceLogClient.listServices();
                if (services != null && !services.isEmpty()) {
                    sb.append("可读日志的纳管源(read_service_logs 的 service 参数用这里的名字):")
                            .append(String.join("、", services)).append('\n');
                }
            } catch (Exception e) {
                log.debug("service list failed (ignored): {}", e.getMessage());
            }
        }
        String result = sb.isEmpty() ? null : sb.toString().stripTrailing();
        if (result != null) {
            envSummaryCache = result;
            envSummaryCacheAt = now;
        }
        return result;
    }

    /**
     * 当前时间行:2026-09-18 周五 11:50(Asia/Shanghai)。
     *
     * <p>「昨天/今天/最近」类请求全靠它——不注入的话模型只能现跑命令查,
     * 而查回来的日期在下一轮又忘了(上下文里的工具结果会被压缩回收)。
     */
    static String currentTimeLine() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(
                "yyyy-MM-dd EEEE HH:mm", java.util.Locale.CHINA);
        return now.format(fmt) + "(" + java.time.ZoneId.systemDefault() + ")";
    }

    /**
     * 从工具消息 content 节点提取纯文本:普通字符串(遗留)或多模态数组
     * (text + image_url 部件,新)。图片以短标记表示,只关心文本的调用方仍正确。
     */
    static String textOfContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String type = part.path("type").asText("");
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part.path("text").asText(""));
                } else if ("image_url".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("(\u56fe\u7247\u9644\u4ef6)");
                }
            }
            return sb.toString();
        }
        return content.asText(null);
    }

    String lastToolResult(List<WireMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            WireMessage m = messages.get(i);
            if ("tool".equals(m.node().path("role").asText())) {
                return textOfContent(m.node().path("content"));
            }
            if ("assistant".equals(m.node().path("role").asText())) {
                break;
            }
        }
        return null;
    }

}
