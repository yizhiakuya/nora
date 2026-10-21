package com.nora.agent.service;

import static com.nora.agent.service.ChatToolExecutor.textOutcome;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.service.ChatToolExecutor.ToolOutcome;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

/** MCP 管理工具:服务器注册、配置、发现与按需调用。 */
@Component
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
    @Tool(name = "manage_mcp", value = "发现外部工具或管理 MCP 连接。list 查看服务器；get 查看脱敏配置；tools 列工具，带 tool 返回完整 schema，call 按 schema 执行（target/tool/arguments）。refresh 连接并发现工具。register 新建远程 url 或 STDIO command/args；update 原地改远程 url/headers，enable/disable 启停，remove 删注册，setPolicy 设置 eager/lazy。已有服务先 get 核对再改，register 自带连接测试。凭据用 manage_env_vars 保存并以 ${KEY} 引用，不能扫描隐藏密钥；缺地址或鉴权先 ask_user。终端只用于必要的运行依赖安装。")
    public ToolOutcome execManageMcp(@P(name="input") ToolInputs.Mcp input, InvocationParameters invocation) {
        // 与分类器共用归一化:create/delete 等别名 → register/remove(两处必须一致)
        String action = RiskClassifier.normalizeMcpAction(input.action() == null ? null : input.action().name());
        String guard = RiskClassifier.validateMcpAction(action);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        try {
            if ("list".equals(action)) {
                return mcpListResult();
            }
            if ("tools".equals(action)) {
                return mcpToolsResult(input);
            }
            if ("call".equals(action)) {
                return mcpCallResult(input);
            }
            if ("register".equals(action)) {
                return mcpRegisterResult(input);
            }
            if ("update".equals(action)) {
                return mcpUpdateResult(input);
            }
            return mcpTargetOpResult(action, input);
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (Texts.isInterruption(e)) throw new java.util.concurrent.CancellationException(e.getMessage());
            // create/refresh 的参数与连接错误:直接作为可自纠错误回给模型
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 300),
                    null, null, false);
        } catch (Exception e) {
            if (Texts.isInterruption(e)) throw new java.util.concurrent.CancellationException(e.getMessage());
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
    private ToolOutcome mcpToolsResult(ToolInputs.Mcp input) {
        String target = Texts.firstNonNull(input.target(), input.name());
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
        String toolName = input.tool();
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
                    return textOutcome(sb.toString(), "工具说明 " + t.name());
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
        return textOutcome(sb.toString(), "工具清单 " + entries.size() + " 个");
    }

    /**
     * manage_mcp action=call:按名调用工具(lazy 服务器的使用通道;eager 服务器
     * 也可用,等价于 mcp__&lt;server&gt;__&lt;tool&gt; 挂载调用)。
     * 参数:target=服务器, tool=工具名, arguments=工具参数对象。
     */
    private ToolOutcome mcpCallResult(ToolInputs.Mcp input) {
        String target = input.target();
        String tool = input.tool();
        if (target == null || target.isBlank() || tool == null || tool.isBlank()) {
            return new ToolOutcome("ERROR: call 需要 target(服务器名或 id)与 tool(工具名)。"
                    + "示例:{\"action\": \"call\", \"target\": \"github\", \"tool\": \"get_me\", \"arguments\": {}}",
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
            argsJson = objectMapper.writeValueAsString(input.arguments() == null ? Map.of() : input.arguments());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        }
        McpServerService.McpToolResult mcpResult = mcpServerService.callToolRich(server.id(), tool.trim(), argsJson);
        if (galleryPrefetcher != null && !mcpResult.isError()) {
            galleryPrefetcher.prefetchFromToolResult(mcpResult.text());
        }
        ToolOutcome outcome = textOutcome(mcpResult.text(), "MCP " + server.name() + "." + tool.trim() + " 执行完成");
        // 结果未知(调用已发出但中断):与挂载工具同语义透传(设计 §5.2)
        return mcpResult.images().isEmpty()
                ? (mcpResult.unknown()
                        ? new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                                outcome.truncated(), outcome.images(), true)
                        : outcome)
                : new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                        outcome.truncated(), mcpResult.images(), mcpResult.unknown());
    }

    /** lazy MCP 的执行、风险与指纹共用对象参数边界。 */
    static JsonNode mcpArguments(ObjectMapper mapper, JsonNode arguments) {
        if (arguments.isMissingNode() || arguments.isNull()) return mapper.createObjectNode();
        if (!arguments.isObject()) throw new IllegalArgumentException("arguments 必须是参数对象");
        return arguments;
    }

    /** manage_mcp action=register:注册 + 自动测试连接(失败不回滚注册)。 */
    private ToolOutcome mcpRegisterResult(ToolInputs.Mcp input) {
        String rName = input.name();
        String rUrl = input.url();
        String rTransport = input.transport() == null ? null : input.transport().name();
        // STDIO(本地进程)注册:command + args + env
        String rCommand = input.command();
        // 推断:给了 command 没给 url/transport → 本地进程形态(模型常省略 transport)
        if ((rTransport == null || rTransport.isBlank())
                && rCommand != null && !rCommand.isBlank()
                && (rUrl == null || rUrl.isBlank())) {
            rTransport = "STDIO";
        }
        // 方言归一化(2026-09-18:实测模型写 transport="http"):与分类器共用
        rTransport = RiskClassifier.normalizeMcpTransport(rTransport);
        List<String> rArgs = input.args() == null ? List.of() : input.args();
        String registerGuard = RiskClassifier.validateMcpRegister(rName, rUrl, rTransport, rCommand, rArgs);
        if (registerGuard != null) {
            return new ToolOutcome("ERROR: " + registerGuard, null, null, false);
        }
        var existing = mcpServerService.findByName(rName);
        if (existing != null) {
            return new ToolOutcome("ERROR: 服务器名「" + rName.trim() + "」已存在。"
                    + ("STDIO".equalsIgnoreCase(existing.transport()) ? "本地启动命令需修改时用 remove 后重新注册；"
                        : "修改地址或鉴权用 update；")
                    + "测试连接和重新拉取工具用 refresh", null, null, false);
        }
        Map<String, String> headers = input.headers() == null ? Map.of() : input.headers();
        Map<String, String> env = input.env() == null ? Map.of() : input.env();
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
            if (Texts.isInterruption(e)) throw new java.util.concurrent.CancellationException(e.getMessage());
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
    private ToolOutcome mcpUpdateResult(ToolInputs.Mcp input) {
        String target = Texts.firstNonNull(input.target(), input.name());
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: update 需要 target(服务器名或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器「" + target + "」。可先用 action=list 查看现有服务器"
                    + "(服务器名不能猜测)", null, null, false);
        }
        String url = input.url();
        Map<String, String> headers = input.headers(); // null=不改，{}=清除
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
            if (Texts.isInterruption(e)) throw new java.util.concurrent.CancellationException(e.getMessage());
            testResult = "连接测试失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200)
                    + "(更新已保留;检查地址/密钥后用 refresh 重试)";
        }
        return new ToolOutcome("已更新 MCP 服务器「" + server.name() + "」"
                + (url != null && !url.isBlank() ? "\n地址: 已更新" : "")
                + (headers != null ? "\n鉴权头: " + (headers.isEmpty() ? "已清除" : "已更新(" + String.join(", ", headers.keySet()) + ")") : "")
                + "\n" + testResult, null, null, false);
    }

    /** manage_mcp 目标操作:refresh / enable / disable / remove(目标 = 名称或数字 id)。 */
    private ToolOutcome mcpTargetOpResult(String action, ToolInputs.Mcp input) {
        // refresh / enable / disable / remove:目标 = 名称或数字 id
        String target = Texts.firstNonNull(input.target(), input.name());
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
            case "get" -> "id=" + server.id() + " · " + server.name() + " · " + server.transport()
                    + "\n连接地址: " + (server.url() == null ? "(本地进程)" : server.url()
                        .replaceFirst("(?<=://)[^/?#]*@", "[REDACTED]@")
                        .replaceFirst("[?#].*", " (查询参数已隐藏)"))
                    + "\n启动命令: " + (server.command() == null ? "(远程连接)" : server.command())
                    + "\n鉴权头(脱敏): " + (server.maskedHeaders() == null ? "{}" : server.maskedHeaders())
                    + "\n环境变量(脱敏): " + (server.maskedEnv() == null ? "{}" : server.maskedEnv())
                    + "\n启用: " + server.enabled() + " · 状态: " + server.status()
                    + " · 工具数: " + server.toolCount() + " · 策略: " + server.toolPolicy()
                    + "\n修改地址/鉴权用 update；省略 headers 会保留已有凭据。测试连接用 refresh。";
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
                String policy = input.toolPolicy() == null ? null : input.toolPolicy().name();
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
