package com.nora.agent.service;


import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;

/**
 * 将工具调用分派到领域服务，统一校验参数、脱敏并限制回填输出。
 * 文件与 MCP 管理由各自执行器处理。
 */
@Component
class ChatToolExecutor {
    static final int MAX_SUCCESS_CHARS = 30_000;
    static final int MAX_FAILURE_CHARS = 10_000;
    private static final int FAILURE_HEAD_CHARS = 6_000;
    private static final int FAILURE_TAIL_CHARS = 3_000;

    private final ObjectMapper objectMapper;
    private final AnnotatedTools annotatedTools;
    private final McpServerService mcpServerService;
    private final AgentWorkspaceService agentWorkspaceService;
    private final GalleryPrefetcher galleryPrefetcher;
    private final EnvVarsService envVarsService;
    private final SessionProjectService sessionProjects;

    RiskClassifier.Risk classifyRisk(String name, String args) {
        return RiskClassifier.classify(name, args, agentWorkspaceService);
    }

    boolean isOutsideWorkspace(String path) {
        if (path == null || path.isBlank()) return true;
        return RiskClassifier.isOutsideWorkspace(path, agentWorkspaceService);
    }

    String redact(String text) {
        return envVarsService == null ? redactToolContent(text) : envVarsService.redact(text);
    }

    <T> T redactValue(T value, Class<T> type) {
        JsonNode tree = objectMapper.valueToTree(value);
        scrubCredentials(tree);
        return objectMapper.convertValue(tree, type);
    }

    void scrubCredentials(JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.fieldNames().forEachRemaining(key -> {
                String normalized = key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                JsonNode value = obj.get(key);
                if (value == null || value.isNull()) return;
                switch (normalized) {
                    case "secret" -> { if (!value.isBoolean()) obj.put(key, "***"); }
                    case "password", "token", "apikey", "authorization", "accesstoken", "refreshtoken" -> obj.put(key, "***");
                    case "headers", "env" -> {
                        if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode fields) {
                            fields.fieldNames().forEachRemaining(field -> fields.put(field, "***"));
                        } else obj.put(key, "***");
                    }
                    case "arguments", "rawargs" -> {
                        if (value.isTextual()) {
                            try {
                                JsonNode args = objectMapper.readTree(value.asText());
                                if (args == null || !args.isContainerNode()) obj.put(key, "{}");
                                else { scrubCredentials(args); obj.put(key, args.toString()); }
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                obj.put(key, "{}");
                            }
                        } else scrubCredentials(value);
                    }
                    default -> {
                        if (value.isTextual()) obj.put(key, redact(value.asText()));
                        else scrubCredentials(value);
                    }
                }
            });
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) array.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(redact(value.asText())));
                else scrubCredentials(value);
            }
        }
    }

    ChatToolExecutor(ObjectMapper objectMapper, AnnotatedTools annotatedTools, McpServerService mcpServerService, AgentWorkspaceService agentWorkspaceService, GalleryPrefetcher galleryPrefetcher, EnvVarsService envVarsService, SessionProjectService sessionProjects) {
        this.objectMapper = objectMapper;
        this.annotatedTools = annotatedTools;
        this.mcpServerService = mcpServerService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.galleryPrefetcher = galleryPrefetcher;
        this.envVarsService = envVarsService;
        this.sessionProjects = sessionProjects;
    }

    interface LiveOutput {
        void text(String chunk);

        default void progress(ChatStepDto.StepProgress progress) {
        }

        /**
         * 用户是否已请求停止本会话轮次(轮询式取消信号)。
         * 默认实现查线程中断标志;ToolStepEmitter 的实现额外查
         * {@link TurnCancellation}(不可被下游消费的标志,2026-09-17 修复
         * 「停止生成杀不掉批量下载」)。
         */
        default boolean cancelled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 分发一次工具调用。守卫拒绝返回三段式错误
     * (拒绝了什么 + 哪条规则 + 正确示例),让模型下一轮自我纠正。
     *
     * <p>2026-09-17:各工具分支拆为独立 handler(见下方 exec* 方法),此处只做分发。
     */
    ToolOutcome executeTool(String name, String args, ToolCallContext context) {
        return boundOutcome(executeRawTool("read_file".equals(name) ? "manage_file" : name, args, context));
    }

    String resolveProjectArgs(String name, String args, String sessionId) {
        if (sessionProjects == null || sessionId == null || !"manage_workspace".equals(name)) return args;
        try {
            var node = objectMapper.readTree(args);
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return args;
            var root = sessionProjects.root(sessionId);
            for (String key : java.util.List.of("path", "filename", "file", "dir", "to")) {
                String value = object.path(key).asText(null);
                if (value != null && !value.isBlank() && !java.nio.file.Path.of(value).isAbsolute())
                    object.put(key, root.resolve(value).normalize().toString());
            }
            if ("list".equals(RiskClassifier.normalizeWorkspaceAction(object.path("action").asText()))
                    && !object.hasNonNull("dir") && !object.hasNonNull("path")) object.put("dir", root.toString());
            return object.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.nio.file.InvalidPathException e) { return args; }
    }

    String restrictRetrievalArgs(String name, String args, com.nora.rag.service.RetrievalService.RetrievalScope scope) {
        if (!"search_knowledge".equals(name) || scope == null || scope.unrestricted()) return args;
        try {
            var node = objectMapper.readTree(args);
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return args;
            if (scope.baseId() != null) object.put("baseId", scope.baseId());
            if (scope.docIds() != null && !scope.docIds().isEmpty()) object.set("docIds", objectMapper.valueToTree(scope.docIds()));
            if (scope.sources() != null && !scope.sources().isEmpty()) object.set("sources", objectMapper.valueToTree(scope.sources()));
            return object.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { return args; }
    }

    private ToolOutcome executeRawTool(String name, String args, ToolCallContext context) {
        if (annotatedTools.contains(name)) return annotatedTools.execute(name, args, context);
        // MCP 挂载工具兜底分发:名字带 mcp__ 前缀 → 路由到对应服务器执行;
        // 输出同样走 bounded 截断与脱敏
        if (mcpServerService != null && name.startsWith("mcp__")) {
            McpServerService.RawServer server = mcpServerService.serverForMountedTool(name);
            if (server == null) {
                // 区分两种失败:格式对但服务器不存在/已停用,还是名字本身就是幻觉
                // (如 mcp__phone_photos_review 少一个下划线)——给可操作的出路。
                boolean malformed = name.indexOf("__", "mcp__".length()) < 0;
                String hint = malformed
                        ? "工具名格式应为 mcp__<服务器名>__<工具名>(服务器名与工具名之间是双下划线);"
                        : "该服务器可能已被禁用或删除;";
                return new ToolOutcome("ERROR: 找不到工具 " + name + " —— " + hint
                        + "先用 manage_mcp action=list 查看可用服务器,再 refresh 拉取工具清单"
                        + "(挂载名以清单为准,不要凭记忆拼写)", null, null, false);
            }
            McpServerService.McpToolResult mcpResult =
                    mcpServerService.callToolRich(server.id(), McpServerService.rawToolName(name), args);
            // 画廊列表预取(2026-09-17):结果里出现 nora-gallery 围栏(photos_showcase)
            // 时立即后台缓存每条媒体的播放流——用户还在看列表的窗口里把手机转码
            // (视频首次 10-15s)与传输都做完,点开即秒播。用未截断全文解析。
            if (galleryPrefetcher != null && !mcpResult.isError()) {
                galleryPrefetcher.prefetchFromToolResult(mcpResult.text());
            }
            ToolOutcome mcpOutcome = textOutcome(mcpResult.text(), "MCP " + server.name() + " 执行完成");
            // 结果未知(调用已发出但中断,远端可能已生效;设计 §5.2):单独状态,
            // 不混入普通 failed——前端橙色警示、运行终态记 partial
            if (mcpResult.unknown()) {
                mcpOutcome = new ToolOutcome(mcpOutcome.content(), mcpOutcome.summary(),
                        mcpOutcome.rowCount(), mcpOutcome.truncated(), mcpOutcome.images(), true);
            }
            // 保留 image 块:图片本体不参与文本截断(避免把 base64 当文本切)，
            // 由回填层按模型识图能力决定是否附上
            return mcpResult.images().isEmpty() ? mcpOutcome
                    : new ToolOutcome(mcpOutcome.content(), mcpOutcome.summary(), mcpOutcome.rowCount(),
                            mcpOutcome.truncated(), mcpResult.images(), mcpOutcome.unknown());
        }
        return textOutcome("ERROR: 未知工具 " + name + "。可用内置工具: "
                + annotatedTools.specifications().stream().map(dev.langchain4j.agent.tool.ToolSpecification::name)
                    .collect(java.util.stream.Collectors.joining(", ")), null);
    }

    static String formatEta(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分" + (seconds % 60 > 0 ? (seconds % 60) + " 秒" : "");
        }
        return (seconds / 3600) + " 小时" + (seconds % 3600 / 60 > 0 ? (seconds % 3600 / 60) + " 分" : "");
    }

    static ToolOutcome textOutcome(String result, String summary) {
        String content = result == null ? "(empty)" : result;
        return new ToolOutcome(content, summary != null ? summary : summarizeRows(content), null, false);
    }

    // 只从键名边界尝试匹配，避免普通长单词在每个字符处重新回溯。
    static final java.util.regex.Pattern TOOL_CREDENTIAL = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w.-])([\"']?(?:[\\w.-]*?(?:api[_-]?key|password|token|secret)|(?:proxy-)?authorization)[\"']?\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*(?:\"|\\\\?$)|'(?:\\\\.|[^'\\\\])*(?:'|\\\\?$)|(?:(?:Bearer|Basic)\\s+)?[^\\s,;\"'}\\]]+)");

    static String redactToolContent(String result) {
        if (result == null) return null;
        return TOOL_CREDENTIAL.matcher(result).replaceAll(match -> {
            String prefix = match.group(1);
            String value = match.group(2);
            boolean jsonField = prefix.startsWith("\"");
            if (jsonField && ("null".equals(value) || prefix.toLowerCase(java.util.Locale.ROOT).startsWith("\"secret\"")
                    && ("true".equals(value) || "false".equals(value)))) {
                return java.util.regex.Matcher.quoteReplacement(match.group());
            }
            char quote = value.charAt(0);
            String masked = quote == '\"' || quote == '\'' ? quote + "[REDACTED]" + quote
                    : jsonField ? "\"[REDACTED]\"" : "[REDACTED]";
            return java.util.regex.Matcher.quoteReplacement(prefix + masked);
        });
    }

    private ToolOutcome boundOutcome(ToolOutcome outcome) {
        String redacted = redact(outcome.content() == null ? "(empty)" : outcome.content());
        boolean failure = redacted.startsWith("ERROR:");
        int budget = failure ? MAX_FAILURE_CHARS : MAX_SUCCESS_CHARS;
        boolean truncated = redacted.length() > budget;
        String content = redacted;
        if (truncated) {
            String reference;
            try {
                if (agentWorkspaceService == null) throw new IllegalStateException("工作区未启用");
                reference = agentWorkspaceService.archiveContext("tool", redacted).readHint();
            } catch (IllegalArgumentException | IllegalStateException e) {
                reference = "完整工具记录未保存: " + e.getMessage() + "；结果不完整，不要重放已执行的写操作。";
            }
            content = (failure ? "ERROR: " : "") + reference + "\n"
                    + redacted.substring(0, failure ? FAILURE_HEAD_CHARS : budget - 600)
                    + "\n…(中间省略，原文 " + redacted.length() + " 字符)…\n"
                    + redacted.substring(redacted.length() - (failure ? FAILURE_TAIL_CHARS : 200));
        }
        return new ToolOutcome(content, redact(outcome.summary()), outcome.rowCount(),
                truncated || Boolean.TRUE.equals(outcome.truncated()), outcome.images(), outcome.unknown(),
                outcome.partial(), outcome.files(), outcome.focusTarget(), outcome.fileErrors(), outcome.fileChanges(), outcome.execution());
    }

    /** 从 TSV 渲染推导一行行数摘要("(3 rows, 12ms)" 页脚)。 */
    private static String summarizeRows(String tsv) {
        int idx = tsv.lastIndexOf("(");
        if (idx >= 0 && tsv.endsWith(")")) {
            return tsv.substring(idx + 1, tsv.length() - 1);
        }
        return null;
    }

    /**
     * 把纳管源名称(或数字 id)解析为 环境模块 id。名称对整个注册表做
     * 不区分大小写匹配(含已暂停的源——/services 会省略它们)。
     */
    record ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                        /** 图片附件(MCP 工具返回的 image 块);空 = 纯文本 */
                        java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                        /** 结果未知(调用已发出但中断,远端可能已生效;设计 §5.2):
                         *  与普通失败区分——前端橙色警示、运行终态记 partial。 */
                        boolean unknown,
                        /** 部分成功(批量任务有成功也有失败;设计 §5.2):
                         *  保留成功/失败/跳过区别,不显示为全量成功。 */
                        boolean partial,
                        java.util.List<com.nora.agent.dto.ViewerFile> files,
                        String focusTarget,
                        java.util.List<ViewerService.FileError> fileErrors,
                        /** 文本写操作的变更记录(对话「已编辑 N 个文件」卡片,2026-09-30):
                         *  仅工作区普通内容文件;内部状态文件/区外文件不记录。空 = 非写操作。 */
                        java.util.List<ChatStepDto.FileChange> fileChanges, com.nora.agent.dto.CommandExecutionDto execution) {

        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown, boolean partial,
                    java.util.List<com.nora.agent.dto.ViewerFile> files, String focusTarget,
                    java.util.List<ViewerService.FileError> fileErrors, java.util.List<ChatStepDto.FileChange> fileChanges) {
            this(content, summary, rowCount, truncated, images, unknown, partial, files, focusTarget, fileErrors, fileChanges, null);
        }

        /** 带文件清单的结果(兼容构造:无 fileChanges)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown, boolean partial,
                    java.util.List<com.nora.agent.dto.ViewerFile> files, String focusTarget,
                    java.util.List<ViewerService.FileError> fileErrors) {
            this(content, summary, rowCount, truncated, images, unknown, partial, files, focusTarget, fileErrors, null);
        }

        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown, boolean partial) {
            this(content, summary, rowCount, truncated, images, unknown, partial, null, null, null);
        }

        /** 纯文本结果(旧行为,保持不变) */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated) {
            this(content, summary, rowCount, truncated, java.util.List.of(), false, false);
        }

        /** 带图片的结果(兼容构造:非 unknown/partial)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images) {
            this(content, summary, rowCount, truncated, images, false, false);
        }

        /** 带 unknown 的结果(兼容构造:非 partial)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown) {
            this(content, summary, rowCount, truncated, images, unknown, false);
        }

        boolean hasImages() {
            return images != null && !images.isEmpty();
        }
    }
}
