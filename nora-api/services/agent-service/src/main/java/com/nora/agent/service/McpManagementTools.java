package com.nora.agent.service;

import static com.nora.agent.service.ChatToolExecutor.bounded;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.service.ChatToolExecutor.LiveOutput;
import com.nora.agent.service.ChatToolExecutor.ToolOutcome;

/** MCP 管理工具:服务器注册、配置、发现与按需调用。 */
class McpManagementTools {
    private final ObjectMapper objectMapper;
    private final McpServerService mcpServerService;
    private final GalleryPrefetcher galleryPrefetcher;

    McpManagementTools(ObjectMapper objectMapper, McpServerService mcpServerService,
            GalleryPrefetcher galleryPrefetcher) {
        this.objectMapper = objectMapper;
        this.mcpServerService = mcpServerService;
        this.galleryPrefetcher = galleryPrefetcher;
    }

    /** manage_mcp handler(2026-09-17 从 executeTool 拆出;内部再拆 list/register/目标操作三段)。 */
    ToolOutcome execManageMcp(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (mcpServerService == null) {
            return new ToolOutcome("ERROR: MCP 管理能力未启用(服务未配置)", null, null, false);
        }
        // 与分类器共用归一化:create/delete 等别名 → register/remove(两处必须一致)
        String action = RiskClassifier.normalizeMcpAction(parsed.datasourceAction());
        String guard = RiskClassifier.validateMcpAction(action);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            if ("list".equals(action)) {
                return mcpListResult();
            }
            if ("tools".equals(action)) {
                return mcpToolsResult(a);
            }
            if ("call".equals(action)) {
                return mcpCallResult(a);
            }
            if ("register".equals(action)) {
                return mcpRegisterResult(a);
            }
            if ("update".equals(action)) {
                return mcpUpdateResult(a);
            }
            return mcpTargetOpResult(action, a);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // create/refresh 的参数与连接错误:直接作为可自纠错误回给模型
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 300),
                    null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: MCP 操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_mcp action=list:服务器清单(含状态/工具数)。 */
    private ToolOutcome mcpListResult() {
        List<McpServerService.ServerView> all = mcpServerService.list();
        if (all.isEmpty()) {
            return new ToolOutcome("(暂无 MCP 服务器)。可用 action=register 注册:"
                    + "{\"action\": \"register\", \"name\": \"名称\", \"url\": \"https://...\"}",
                    null, null, false);
        }
        StringBuilder sb = new StringBuilder("已注册 MCP 服务器:\n");
        for (McpServerService.ServerView s : all) {
            sb.append("id=").append(s.id())
                    .append(s.enabled() ? "" : " [已停用]")
                    .append(" ").append(s.name())
                    .append(" · ").append(s.transport())
                    .append(" · ").append(s.status())
                    .append(s.statusDetail() != null ? "(" + Texts.abbreviate(s.statusDetail(), 80) + ")" : "")
                    .append(" · 工具数 ").append(s.toolCount())
                    .append("lazy".equalsIgnoreCase(s.toolPolicy()) ? " · lazy(按需)" : "")
                    .append('\n');
        }
        return new ToolOutcome(sb.toString(), null, null, false);
    }

    /**
     * manage_mcp action=tools:某服务器的工具清单(读缓存快照,不触发远端)。
     * 带 tool 参数时返回该工具的完整 inputSchema(设计 §6:lazy 路径
     * 「能力摘要 → 找到工具 → 读取完整参数说明 → 校验并调用」——
     * 缓存里已有 schema,调用前用它精确构造参数,不靠猜)。
     */
    private ToolOutcome mcpToolsResult(JsonNode a) {
        String target = Texts.firstNonNull(a.path("target").asText(null),
                Texts.firstNonNull(a.path("name").asText(null), a.path("server").asText(null)));
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: tools 需要 target 参数(服务器名或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器 \"" + target + "\"。可用服务器:\n"
                    + mcpServerService.list(), null, null, false);
        }
        List<McpServerService.ToolEntry> entries = mcpServerService.cachedTools(server.id());
        if (entries == null || entries.isEmpty()) {
            return new ToolOutcome("(服务器「" + server.name() + "」还没有工具清单快照——"
                    + "先用 action=refresh target=" + server.name() + " 拉取)", null, null, false);
        }
        // 单工具 schema 模式(带 tool 参数):完整参数说明,供精确构造调用
        String toolName = Texts.firstNonNull(a.path("tool").asText(null), a.path("toolName").asText(null));
        if (toolName != null && !toolName.isBlank()) {
            for (McpServerService.ToolEntry t : entries) {
                if (toolName.trim().equals(t.name())) {
                    StringBuilder sb = new StringBuilder("服务器「").append(server.name())
                            .append("」的工具 ").append(t.name()).append(" 完整说明:\n");
                    sb.append("描述: ").append(t.description() == null || t.description().isBlank()
                            ? "(无描述)" : t.description()).append('\n');
                    sb.append("参数 schema(按此构造 arguments;required 中的字段必填):\n");
                    sb.append(t.inputSchema() == null ? "(该工具未声明参数 schema——按描述调用,不确定时传空对象 {})"
                            : t.inputSchema().toPrettyString());
                    return bounded(sb.toString(), "工具说明 " + t.name());
                }
            }
            return new ToolOutcome("ERROR: 服务器「" + server.name() + "」没有名为 \"" + toolName
                    + "\" 的工具(以 action=tools 清单为准,不要凭记忆拼写)", null, null, false);
        }
        StringBuilder sb = new StringBuilder("服务器「").append(server.name()).append("」的工具清单(共 ")
                .append(entries.size()).append(" 个;lazy 服务器用 action=call 按名调用;"
                        + "调用前可 action=tools target=" + server.name() + " tool=<工具名> 查看完整参数说明):\n");
        for (McpServerService.ToolEntry t : entries) {
            sb.append("- ").append(t.name());
            String desc = t.description() == null ? "" : t.description();
            if (!desc.isBlank()) {
                sb.append(": ").append(Texts.abbreviate(desc, 160));
            }
            sb.append('\n');
        }
        return bounded(sb.toString(), "工具清单 " + entries.size() + " 个");
    }

    /**
     * manage_mcp action=call:按名调用工具(lazy 服务器的使用通道;eager 服务器
     * 也可用,等价于 mcp__&lt;server&gt;__&lt;tool&gt; 挂载调用)。
     * 参数:target=服务器, tool=工具名, arguments=工具参数 JSON 字符串或对象。
     */
    private ToolOutcome mcpCallResult(JsonNode a) {
        String target = Texts.firstNonNull(a.path("target").asText(null), a.path("server").asText(null));
        String tool = Texts.firstNonNull(a.path("tool").asText(null), a.path("toolName").asText(null));
        if (target == null || target.isBlank() || tool == null || tool.isBlank()) {
            return new ToolOutcome("ERROR: call 需要 target(服务器名或 id)与 tool(工具名)。"
                    + "示例:{\"action\": \"call\", \"target\": \"github\", \"tool\": \"get_me\", \"arguments\": \"{}\"}",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器 \"" + target + "\"。可用服务器:\n"
                    + mcpServerService.list(), null, null, false);
        }
        if (!server.enabled()) {
            return new ToolOutcome("ERROR: 服务器「" + server.name() + "」已停用——先 action=enable target="
                    + server.name(), null, null, false);
        }
        String argsJson;
        try {
            argsJson = mcpArguments(objectMapper, a.path("arguments")).toString();
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        }
        McpServerService.McpToolResult mcpResult = mcpServerService.callToolRich(server.id(), tool.trim(), argsJson);
        if (galleryPrefetcher != null && !mcpResult.isError()) {
            galleryPrefetcher.prefetchFromToolResult(mcpResult.text());
        }
        ToolOutcome outcome = bounded(mcpResult.text(), "MCP " + server.name() + "." + tool.trim() + " 执行完成");
        // 结果未知(调用已发出但中断):与挂载工具同语义透传(设计 §5.2)
        return mcpResult.images().isEmpty()
                ? (mcpResult.unknown()
                        ? new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                                outcome.truncated(), outcome.images(), true)
                        : outcome)
                : new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                        outcome.truncated(), mcpResult.images(), mcpResult.unknown());
    }

    /** lazy MCP 的执行与指纹共用同一参数解释;省略或空白字符串表示空对象。 */
    static JsonNode mcpArguments(ObjectMapper mapper, JsonNode arguments) {
        if (arguments.isMissingNode() || arguments.isNull()) {
            return mapper.createObjectNode();
        }
        JsonNode parsed = arguments;
        if (arguments.isTextual()) {
            String raw = arguments.asText();
            try {
                parsed = mapper.readTree(raw.isBlank() ? "{}" : raw);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("arguments 不是合法 JSON，请传参数对象或对象的 JSON 字符串", e);
            }
        }
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("arguments 必须是对象或对象的 JSON 字符串");
        }
        return parsed;
    }

    /** manage_mcp action=register:注册 + 自动测试连接(失败不回滚注册)。 */
    private ToolOutcome mcpRegisterResult(JsonNode a) {
        String rName = a.path("name").asText(null);
        String rUrl = a.path("url").asText(null);
        String rTransport = a.path("transport").asText(null);
        // STDIO(本地进程)注册:command + args + env
        String rCommand = a.path("command").asText(null);
        // 推断:给了 command 没给 url/transport → 本地进程形态(模型常省略 transport)
        if ((rTransport == null || rTransport.isBlank())
                && rCommand != null && !rCommand.isBlank()
                && (rUrl == null || rUrl.isBlank())) {
            rTransport = "STDIO";
        }
        // 方言归一化(2026-09-18:实测模型写 transport="http"):与分类器共用
        rTransport = RiskClassifier.normalizeMcpTransport(rTransport);
        List<String> rArgs = new java.util.ArrayList<>();
        if (a.path("args").isArray()) {
            for (JsonNode n : a.path("args")) {
                rArgs.add(n.asText(""));
            }
        }
        String registerGuard = RiskClassifier.validateMcpRegister(rName, rUrl, rTransport, rCommand, rArgs);
        if (registerGuard != null) {
            return new ToolOutcome("ERROR: " + registerGuard, null, null, false);
        }
        if (mcpServerService.findByName(rName) != null) {
            return new ToolOutcome("ERROR: 服务器名「" + rName.trim() + "」已存在。如需改用 remove 后重新注册,"
                    + "或用 refresh 重新拉取工具", null, null, false);
        }
        // headers/env 从原始 args 取,只传给服务层——值不进步骤/审批/对话记录
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        JsonNode h = a.path("headers");
        if (h.isObject()) {
            h.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText("")));
        }
        Map<String, String> env = new java.util.LinkedHashMap<>();
        JsonNode envNode = a.path("env");
        if (envNode.isObject()) {
            envNode.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText("")));
        }
        McpServerService.ServerView created = mcpServerService.create(rName, rUrl, rTransport,
                headers.isEmpty() ? null : headers,
                rCommand, rArgs.isEmpty() ? null : rArgs, env.isEmpty() ? null : env);
        // 注册后自动测试连接(refresh):对齐 manage_datasource create 后自动 test 的语义;
        // 连接失败不回滚注册(注册本身成功,失败原因如实报告,用户可稍后重试 refresh)
        String testResult;
        try {
            List<McpServerService.ToolEntry> toolEntries = mcpServerService.refresh(created.id());
            testResult = "连接成功,发现 " + toolEntries.size() + " 个工具"
                    + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                    + (toolEntries.size() > 10 ? " 等" : ""))
                    + "。工具已挂载(mcp__" + created.name() + "__*),下轮对话可直接调用";
        } catch (Exception e) {
            testResult = "连接测试失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200)
                    + "(注册已保留;可检查地址/鉴权后用 refresh 重试)";
        }
        return new ToolOutcome("已注册 MCP 服务器(id=" + created.id() + "): " + created.name()
                + " · " + created.transport() + "\n" + testResult, null, null, false);
    }

    /**
     * manage_mcp action=update(2026-09-29):原地改既有远程服务器的 url /
     * headers(换密钥/换地址不必 remove+重新注册)。给什么改什么:
     * url 省略=不动;headers 省略=不动、显式 {} = 清除。
     * 值不落步骤/对话记录(仅服务层),更新后自动 refresh 重建工具缓存。
     */
    private ToolOutcome mcpUpdateResult(JsonNode a) {
        String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: update 需要 target(服务器名或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器「" + target + "」。可先用 action=list 查看现有服务器"
                    + "(服务器名不能猜测)", null, null, false);
        }
        String url = a.path("url").asText(null);
        Map<String, String> headers = null; // null=不改;{} = 清除
        JsonNode h = a.path("headers");
        if (h.isObject()) {
            Map<String, String> collected = new java.util.LinkedHashMap<>();
            h.fields().forEachRemaining(e -> collected.put(e.getKey(), e.getValue().asText("")));
            headers = collected;
        }
        if ((url == null || url.isBlank()) && headers == null) {
            return new ToolOutcome("ERROR: update 至少需要 url 或 headers 之一(给什么改什么)。"
                    + "示例:{\"action\": \"update\", \"target\": \"" + server.name()
                    + "\", \"headers\": {\"Authorization\": \"Bearer 新密钥\"}}", null, null, false);
        }
        if (url != null && !url.isBlank() && !url.trim().startsWith("http")) {
            return new ToolOutcome("ERROR: url 必须是 http(s) 地址(当前: " + Texts.abbreviate(url, 80) + ")",
                    null, null, false);
        }
        try {
            McpServerService.ServerView updated = mcpServerService.updateRemote(server.id(), url, headers);
            if (updated == null) {
                return new ToolOutcome("ERROR: 服务器「" + server.name() + "」不是远程形态(或已被删除),"
                        + "无法用 update 修改;STDIO 本地进程请 remove 后重新 register", null, null, false);
            }
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage(), 300), null, null, false);
        }
        // 更新后自动重连测试(与 register 后自动 refresh 同语义):凭证是否正确
        // 当场见分晓,失败如实报告(更新已保留,可再改)
        String testResult;
        try {
            List<McpServerService.ToolEntry> toolEntries = mcpServerService.refresh(server.id());
            testResult = "连接成功,发现 " + toolEntries.size() + " 个工具"
                    + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                    + (toolEntries.size() > 10 ? " 等" : ""))
                    + "。已用新配置生效";
        } catch (Exception e) {
            testResult = "连接测试失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200)
                    + "(更新已保留;检查地址/密钥后用 refresh 重试)";
        }
        return new ToolOutcome("已更新 MCP 服务器「" + server.name() + "」"
                + (url != null && !url.isBlank() ? "\n地址: 已更新" : "")
                + (headers != null ? "\n鉴权头: " + (headers.isEmpty() ? "已清除" : "已更新(" + String.join(", ", headers.keySet()) + ")") : "")
                + "\n" + testResult, null, null, false);
    }

    /** manage_mcp 目标操作:refresh / enable / disable / remove(目标 = 名称或数字 id)。 */
    private ToolOutcome mcpTargetOpResult(String action, JsonNode a) {
        // refresh / enable / disable / remove:目标 = 名称或数字 id
        String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: 缺少目标服务器(target = 名称或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器「" + target + "」。可先用 action=list 查看现有服务器"
                    + "(服务器名不能猜测)", null, null, false);
        }
        String content = switch (action) {
            case "refresh" -> {
                List<McpServerService.ToolEntry> toolEntries = mcpServerService.refresh(server.id());
                boolean lazy = "lazy".equalsIgnoreCase(server.toolPolicy());
                yield "已连接「" + server.name() + "」,发现 " + toolEntries.size() + " 个工具"
                        + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                        + (toolEntries.size() > 10 ? " 等" : ""))
                        + (lazy ? "(lazy 策略:用 action=tools 查清单、action=call 调用)"
                                : "(已挂载为 mcp__" + server.name() + "__*,下轮可直接调用)");
            }
            case "enable" -> mcpServerService.setEnabled(server.id(), true)
                    ? "已启用「" + server.name() + "」。工具将在下轮对话挂载(缓存过工具清单则立即可用)"
                    : "ERROR: 启用失败,服务器可能已被删除";
            case "disable" -> mcpServerService.setEnabled(server.id(), false)
                    ? "已停用「" + server.name() + "」。其工具不再挂载"
                    : "ERROR: 停用失败,服务器可能已被删除";
            case "setpolicy" -> {
                String policy = a.path("toolPolicy").asText(null);
                boolean ok = mcpServerService.setToolPolicy(server.id(), policy);
                yield ok ? "已把「" + server.name() + "」的工具加载策略设为 " + policy
                        + ("lazy".equalsIgnoreCase(policy)
                        ? "(其工具不再挂载;用 action=tools 查清单、action=call 调用,省每轮上下文)"
                        : "(其工具重新挂载为 mcp__" + server.name() + "__*,下轮可直接调用)")
                        : "ERROR: 策略更新失败,服务器可能已被删除";
            }
            default -> mcpServerService.delete(server.id())
                    ? "已删除 MCP 服务器「" + server.name() + "」及其连接"
                    : "ERROR: 删除失败,服务器可能已被删除";
        };
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** 工具名清单(最多 10 个,逗号分隔;register/refresh 的结果文案共用)。 */
    private static String formatToolNames(List<McpServerService.ToolEntry> toolEntries) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(toolEntries.size(), 10); i++) {
            names.append(i > 0 ? ", " : "").append(toolEntries.get(i).name());
        }
        return names.toString();
    }

}
