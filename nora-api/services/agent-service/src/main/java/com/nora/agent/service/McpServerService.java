package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;

/**
 * MCP(Model Context Protocol)服务器注册表 + 客户端生命周期,
 * 供 agent 动态挂载工具。
 *
 * <p>设计:
 * <ul>
 *   <li>传输:STREAMABLE(HTTP)与 SSE 走 JDK HttpClient;STDIO 起本地子进程
 *       (npx/node/docker ...)经 stdin/stdout 跑 JSON-RPC。命令在注册时预检
 *       解析(运行时缺失 → 可操作的报错,而不是一个死注册)。</li>
 *   <li>客户端懒连接并按 server id 池化;{@link #refresh} 重跑
 *       initialize + tools/list 并把工具清单快照进 {@code tools_cache}
 *       (编排层读缓存,组装提示词时绝不阻塞在远端调用上)。</li>
 *   <li>工具命名:{@code mcp__<server>__<tool>} —— 跨服务器无碰撞、路由可逆。</li>
 *   <li>机密:HTTP 头 / stdio env 值存库、读取时脱敏,原文仅在连接时使用。</li>
 *   <li>stdio 进程生命周期:子进程树在 evict/disable/delete/关闭时被杀
 *       (Windows: taskkill /T;否则 npx.cmd → node 的孙进程会孤儿化)。</li>
 * </ul>
 */
@Service
public class McpServerService {

    /**
     * 工具面版本号:任何改变「挂载工具集」的写操作(create/delete/setEnabled/
     * refresh/updateRemoteCredentials)自增。tools spec 的 token 估算缓存按此
     * 失效——否则运行期注册/刷新 MCP 后压缩触发偏晚(2026-09-18 修复)。
     */
    private final java.util.concurrent.atomic.AtomicLong toolsRevision = new java.util.concurrent.atomic.AtomicLong();

    /** 当前工具面版本(供 tools spec 估算缓存做失效判断)。 */
    public long toolsRevision() {
        return toolsRevision.get();
    }

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);


    private final JdbcTemplate jdbcTemplate;
    /** 客户端连接池 + STDIO 进程树(从本类拆出,2026-09-17)。 */
    private final McpClientPool clientPool;
    private final ObjectMapper objectMapper;
    /**
     * 自定义环境变量(设置页「环境变量」;可空=测试构造)。
     * 连接建立时对 url/headers/env/command/args 做 {@code ${VAR}} 替换
     * (2026-09-29):密钥不进对话记录/数据库明文——用户把 key 存设置页,
     * MCP 注册用占位符引用,是「复用本机已有密钥」的干净通道。
     */
    private final AppSettingStore appSettingStore;
    /**
     * 本地是否信任远端 readOnlyHint 声明(2026-09-20 验收 F2,设计 §6):
     * 默认 false——远端注解只是参考,仅当本地配置显式信任时,声明只读的
     * 工具才允许调用中断后自动重放。配置项 nora.agent.mcp.trust-readonly-hints。
     */
    private final boolean trustReadOnlyHints;

    /** 测试便捷构造(不信任远端只读声明)。 */
    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RelayMediaRouter relayRouter) {
        this(jdbcTemplate, objectMapper, relayRouter, false, null);
    }

    /** 测试便捷构造(指定信任开关;不接设置存储)。 */
    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RelayMediaRouter relayRouter,
                            boolean trustReadOnlyHints) {
        this(jdbcTemplate, objectMapper, relayRouter, trustReadOnlyHints, null);
    }

    /** Spring 构造(2026-09-29):接设置存储以支持 ${VAR} 替换。 */
    @org.springframework.beans.factory.annotation.Autowired
    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RelayMediaRouter relayRouter,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${nora.agent.mcp.trust-readonly-hints:false}") boolean trustReadOnlyHints,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            AppSettingStore appSettingStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clientPool = new McpClientPool(objectMapper, relayRouter);
        this.trustReadOnlyHints = trustReadOnlyHints;
        this.appSettingStore = appSettingStore;
    }

    /**
     * {@code ${VAR}} 替换(连接建立前;设置页「环境变量」的值):
     * 未定义的变量保持原样(连接会以字面量失败,可据此提示用户去设置)。
     * 仅作用于连接用原始行——脱敏视图与 DB 里存的仍是占位符(密钥不落库)。
     */
    private String substituteEnv(String value) {
        if (value == null || value.isEmpty() || appSettingStore == null || !value.contains("${")) {
            return value;
        }
        Map<String, String> vars = com.nora.agent.controller.EnvVarsController.resolveForExecution(appSettingStore);
        if (vars.isEmpty()) {
            return value;
        }
        String out = value;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** 对原始行的连接相关字段做 ${VAR} 替换(不改 DB;仅连接/调用路径)。 */
    private RawServer withResolvedEnv(RawServer raw) {
        if (raw == null || appSettingStore == null) {
            return raw;
        }
        return new RawServer(raw.id(), raw.name(),
                substituteEnv(raw.url()), raw.transport(),
                substituteEnv(raw.headers()), substituteEnv(raw.command()),
                substituteEnv(raw.args()), substituteEnv(raw.env()),
                raw.enabled(), raw.toolPolicy());
    }

    // ---------- 注册表 CRUD ----------

    /** 列出服务器(机密脱敏、不含工具缓存载荷;排除软删)。 */
    public List<ServerView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, status, status_detail, tools_cache, tool_policy FROM mcp_server WHERE deleted_at IS NULL ORDER BY id",
                (rs, i) -> viewOf(rs));
    }

    /**
     * 注册服务器;返回机密脱敏后的视图。
     *
     * @param command  STDIO 时:可执行命令(npx / node / docker ...);其余传输为 null
     * @param args     STDIO 时:argv 数组(如 ["-y", "@scope/server"]);可为空
     * @param env      STDIO 时:追加的环境变量(API key 等);值脱敏后展示
     */
    public ServerView create(String name, String url, String transport, Map<String, String> headers,
                             String command, List<String> args, Map<String, String> env) {
        String t = transport == null || transport.isBlank() ? "STREAMABLE" : transport.trim().toUpperCase();
        if (!List.of("STREAMABLE", "SSE", "STDIO").contains(t)) {
            throw new IllegalArgumentException("transport 只支持 STREAMABLE / SSE / STDIO");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        // 服务器名进入挂载工具名 mcp__<server>__<tool>:上游 OpenAI 兼容 API 对
        // function name 有 ^[a-zA-Z0-9_-]+$ 约束,且含 "__" 会让挂载名解析
        // (取第二个 __ 前的段)错位——注册时就拒绝,别等每轮对话 400
        if (!name.trim().matches("^[a-zA-Z0-9_-]+$") || name.trim().contains("__")) {
            throw new IllegalArgumentException("服务器名只能包含字母、数字、下划线、连字符,且不能含连续下划线");
        }
        if ("STDIO".equals(t)) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("STDIO 必须提供 command(可执行命令,如 npx / node / docker)");
            }
            // 预检:命令必须在本机可解析——换环境部署时缺 Node.js 等依赖
            // 会在这里拿到可操作的报错,而不是注册完连接永远失败
            String resolved = McpCommandResolver.resolveCommand(command.trim());
            if (resolved == null) {
                throw new IllegalArgumentException("未找到命令「" + command.trim() + "」——stdio 服务器需要本机已安装对应运行时"
                        + "(Node 包需 Node.js / npx;Python 包需 uv / uvx)。也可改用 Docker 方式:"
                        + "command=docker, args=[\"run\",\"-i\",\"--rm\",\"镜像名\"]");
            }
            jdbcTemplate.update(
                    "INSERT INTO mcp_server (name, transport, command, args, env) VALUES (?, ?, ?, ?, ?)",
                    name.trim(), t, command.trim(),
                    args == null || args.isEmpty() ? null : writeJsonList(args),
                    env == null || env.isEmpty() ? null : writeJson(env));
        } else {
            if (url == null || !url.startsWith("http")) {
                throw new IllegalArgumentException("url 必须是 http(s) 地址");
            }
            jdbcTemplate.update(
                    "INSERT INTO mcp_server (name, url, transport, headers) VALUES (?, ?, ?, ?)",
                    name.trim(), url.trim(), t, headers == null || headers.isEmpty() ? null : writeJson(headers));
        }
        toolsRevision.incrementAndGet();
        return getByName(name.trim());
    }

    /** 软删服务器(释放名称)并关闭池化客户端(stdio:杀进程树)。 */
    public boolean delete(long id) {
        clientPool.evictClient(id);
        boolean deleted = jdbcTemplate.update(
                "UPDATE mcp_server SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
        if (deleted) {
            toolsRevision.incrementAndGet();
        }
        return deleted;
    }

    /**
     * 原地更新既有远程服务器的 url/headers(OAuth token 刷新路径):
     * 池化客户端被驱逐,下次连接用新凭证。返回刷新后的视图,未知时 null。
     */
    public ServerView updateRemoteCredentials(long id, String url, Map<String, String> headers) {
        clientPool.evictClient(id);
        int updated = jdbcTemplate.update(
                "UPDATE mcp_server SET url = COALESCE(?, url), headers = ?, status='untested', status_detail=NULL, tools_cache=NULL WHERE id = ? AND transport <> 'STDIO' AND deleted_at IS NULL",
                url == null || url.isBlank() ? null : url.trim(),
                headers == null || headers.isEmpty() ? null : writeJson(headers),
                id);
        if (updated > 0) {
            toolsRevision.incrementAndGet(); // tools_cache 被清空 = 挂载面变化
        }
        return updated > 0 ? queryOne(VIEW_SELECT + " WHERE id = ? AND deleted_at IS NULL", id) : null;
    }

    /**
     * agent 通道的远程服务器更新(manage_mcp action=update,2026-09-29):
     * url 与 headers 都是「给了才改」——只换密钥不必重传 url、只换地址不动
     * 凭证;headers 显式传空对象 {} 表示清除全部鉴权头。更新后清空工具
     * 缓存(挂载面待 refresh 重建),返回刷新后的脱敏视图。
     *
     * <p>为什么需要它(工具面修复):此前「改地址/换密钥」在工具面上无合法
     * 通道——密钥被平台脱敏后 register 无法重放,只能 remove + 重新 register
     * (历史会话实测:模型在阻塞点上打转 30+ 步)。
     */
    public ServerView updateRemote(long id, String url, Map<String, String> headers) {
        boolean urlGiven = url != null && !url.isBlank();
        boolean headersGiven = headers != null;
        if (!urlGiven && !headersGiven) {
            throw new IllegalArgumentException("update 至少需要 url 或 headers 之一(给什么改什么)");
        }
        clientPool.evictClient(id);
        StringBuilder sql = new StringBuilder(
                "UPDATE mcp_server SET status='untested', status_detail=NULL, tools_cache=NULL");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (urlGiven) {
            sql.append(", url = ?");
            args.add(url.trim());
        }
        if (headersGiven) {
            sql.append(", headers = ?");
            args.add(headers.isEmpty() ? null : writeJson(headers));
        }
        sql.append(" WHERE id = ? AND transport <> 'STDIO' AND deleted_at IS NULL");
        args.add(id);
        int updated = jdbcTemplate.update(sql.toString(), args.toArray());
        if (updated > 0) {
            toolsRevision.incrementAndGet(); // tools_cache 被清空 = 挂载面变化
        }
        return updated > 0 ? queryOne(VIEW_SELECT + " WHERE id = ? AND deleted_at IS NULL", id) : null;
    }

    /** 启用/停用服务器;停用同时丢弃池化客户端。 */
    public boolean setEnabled(long id, boolean enabled) {
        if (!enabled) {
            clientPool.evictClient(id);
        }
        boolean ok = jdbcTemplate.update("UPDATE mcp_server SET status='untested', status_detail=NULL WHERE id = ? AND deleted_at IS NULL",
                id) >= 0
                && jdbcTemplate.update("UPDATE mcp_server SET enabled = ? WHERE id = ? AND deleted_at IS NULL", enabled, id) > 0;
        if (ok) {
            toolsRevision.incrementAndGet();
        }
        return ok;
    }

    /**
     * 按数字 id 或精确名称解析服务器(脱敏视图)。供 agent 的
     * {@code manage_mcp} 工具定位 enable/disable/refresh/remove 目标;
     * 未找到时 null。
     */
    public ServerView findByNameOrId(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String t = target.trim();
        if (t.matches("\\d+")) {
            ServerView byId = queryOne(VIEW_SELECT + " WHERE id = ? AND deleted_at IS NULL", Long.parseLong(t));
            if (byId != null) {
                return byId;
            }
        }
        return getByName(t);
    }

    /**
     * 取一条原始行(机密未脱敏——仅内部使用;排除软删)。
     * 连接字段经 {@code ${VAR}} 替换(设置页环境变量;2026-09-29)。
     */
    public RawServer rawById(long id) {
        List<RawServer> rows = jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, tool_policy FROM mcp_server WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rawOf(rs),
                id);
        return rows.isEmpty() ? null : withResolvedEnv(rows.get(0));
    }

    /**
     * 列出启用服务器的原始行(内部:连接建立用;排除软删)。
     * 连接字段经 {@code ${VAR}} 替换(设置页环境变量;2026-09-29)。
     */
    public List<RawServer> rawEnabled() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, tool_policy FROM mcp_server WHERE enabled = TRUE AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> withResolvedEnv(rawOf(rs)));
    }

    /**
     * 设置工具加载策略(2026-09-18 P2-9):
     * eager=工具直接挂载(每轮注入 tools spec);lazy=按需(不挂载,
     * agent 经 manage_mcp action=tools/call 使用)。
     *
     * @return 是否更新到行;非法策略抛 IllegalArgumentException
     */
    public boolean setToolPolicy(long id, String policy) {
        String p = policy == null ? "" : policy.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"eager".equals(p) && !"lazy".equals(p)) {
            throw new IllegalArgumentException("toolPolicy 只支持 eager / lazy,收到: " + policy);
        }
        boolean ok = jdbcTemplate.update(
                "UPDATE mcp_server SET tool_policy = ? WHERE id = ? AND deleted_at IS NULL", p, id) > 0;
        if (ok) {
            toolsRevision.incrementAndGet(); // 挂载面变化:tools spec 估算缓存失效
        }
        return ok;
    }

    // ---------- 连接 + 工具 ----------

    /**
     * 连接(或复用)服务器客户端,跑 initialize + tools/list,把工具快照进
     * {@code tools_cache} 并更新状态。返回工具清单。
     */
    public List<ToolEntry> refresh(long id) {
        RawServer server = rawById(id);
        if (server == null) {
            throw new IllegalArgumentException("mcp server not found: " + id);
        }
        McpSchema.ListToolsResult tools;
        try {
            McpSyncClient client = clientPool.clientFor(server);
            tools = client.listTools();
        } catch (Exception e) {
            // 失败即驱逐池中客户端(可能是 initialize 成功但 listTools 抖动的
            // 半死连接):与 callTool 的自愈语义一致,下次 refresh 用全新连接
            clientPool.evictClient(id);
            String msg = friendlyConnectError(e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("mcp tools/list failed for server {} ({}): {}", id, server.name(), shorten(msg));
            jdbcTemplate.update("UPDATE mcp_server SET status='error', status_detail=? WHERE id = ?",
                    shorten(msg), id);
            throw new IllegalStateException("MCP 连接失败: " + shorten(msg));
        }
        List<ToolEntry> entries = new ArrayList<>();
        for (McpSchema.Tool tool : tools.tools()) {
            // SDK 的 inputSchema 可能是 POJO(JsonSchema);统一转 JsonNode 存快照。
            // annotations.readOnlyHint 一并存快照(2026-09-20):供「有副作用调用
            // 断线后不盲目重放」判定——仅明确声明只读的工具才允许自动重连重试。
            entries.add(new ToolEntry(tool.name(), tool.description(),
                    tool.inputSchema() == null ? null : objectMapper.valueToTree(tool.inputSchema()),
                    tool.annotations() == null ? null : tool.annotations().readOnlyHint()));
        }
        ObjectNode cache = objectMapper.createObjectNode();
        ArrayNode arr = cache.putArray("tools");
        for (ToolEntry entry : entries) {
            ObjectNode n = arr.addObject();
            n.put("name", entry.name());
            n.put("description", entry.description() == null ? "" : entry.description());
            if (entry.inputSchema() != null) {
                n.set("inputSchema", entry.inputSchema());
            }
            if (entry.readOnlyHint() != null) {
                n.put("readOnlyHint", entry.readOnlyHint());
            }
        }
        jdbcTemplate.update("UPDATE mcp_server SET status='connected', status_detail=NULL, tools_cache=? WHERE id = ?",
                cache.toString(), id);
        toolsRevision.incrementAndGet(); // 工具清单快照更新 = 挂载面变化
        return entries;
    }

    /**
     * 调用服务器上的工具。客户端按需(重)连接。
     *
     * @return 工具结果内容的面向 LLM 渲染
     */
    public String callTool(long serverId, String toolName, String argsJson) {
        return callToolRich(serverId, toolName, argsJson).text();
    }

    /**
     * {@link #callTool} 的富变体:保留 image 内容块,让编排层以多模态部件
     * 喂给支持视觉的模型。文本渲染与旧行为一致(图片留一行尺寸占位)。
     *
     * <p>重试策略(2026-09-20,架构设计 §9.2「重连和重做是两个决定」;
     * 验收 F2/F7 修正):
     * <ul>
     *   <li><b>本地参数解析失败</b> → 请求从未发出:返回可纠正的参数错误,
     *       <b>不是</b> unknown(验收 F7:解析失败不能报"可能已在远端生效");</li>
     *   <li><b>连接建立失败</b>(初始化未完成,调用尚未发出)→ 驱逐死连接、
     *       重连一次(任何工具都安全);</li>
     *   <li><b>调用中断</b>(已发出、结果未知)→ 默认保留 unknown 不重放;
     *       仅当<b>本地信任配置</b>({@code nora.agent.mcp.trust-readonly-hints},
     *       默认 false)开启且该工具声明 readOnlyHint=true 时才重连重试——
     *       远端注解只是参考,执行策略按本地配置(设计 §6;验收 F2)。</li>
     * </ul>
     */
    public McpToolResult callToolRich(long serverId, String toolName, String argsJson) {
        RawServer server = rawById(serverId);
        if (server == null) {
            return McpToolResult.error("ERROR: mcp server " + serverId + " 不存在");
        }
        // 阶段 0:本地参数解析——失败即返回可纠正错误,请求不发出(验收 F7)
        Map<String, Object> args;
        try {
            args = argsJson == null || argsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(argsJson, Map.class);
        } catch (Exception parseFailure) {
            return McpToolResult.error("ERROR: 工具参数不是合法 JSON(" + toolName + "): "
                    + shorten(parseFailure.getMessage() == null ? parseFailure.toString() : parseFailure.getMessage())
                    + " —— 参数未通过本地校验,请求没有发出。请修正参数后重试");
        }
        // 阶段 1:连接(或复用)客户端——初始化失败 = 调用尚未发出,重连一次安全
        McpSyncClient client;
        try {
            client = clientPool.clientFor(server);
        } catch (Exception first) {
            log.info("mcp connect failed (server={}), reconnecting once: {}", server.name(),
                    shorten(first.getMessage() == null ? first.toString() : first.getMessage()));
            clientPool.evictClient(serverId);
            try {
                client = clientPool.clientFor(server);
            } catch (Exception e) {
                String msg = friendlyConnectError(e.getMessage() == null ? e.toString() : e.getMessage());
                log.warn("mcp connect failed after reconnect: server={} tool={}: {}", server.name(), toolName, shorten(msg));
                return McpToolResult.error("ERROR: MCP 工具调用失败(" + toolName + "): " + shorten(msg));
            }
        }
        // 阶段 2:实际调用——中断 = 结果未知(可能已生效),重试按本地信任配置决策
        try {
            return renderResultRich(client.callTool(new McpSchema.CallToolRequest(toolName, args)));
        } catch (Exception callFailure) {
            clientPool.evictClient(serverId);
            String raw = callFailure.getMessage() == null ? callFailure.toString() : callFailure.getMessage();
            if (trustReadOnlyHints && declaredReadOnly(serverId, toolName)) {
                // 本地信任 + 工具声明只读:重连重试一次(可重复操作,无副作用)
                log.info("mcp read-only callTool failed (server={} tool={}), retrying once (local trust enabled): {}",
                        server.name(), toolName, shorten(raw));
                try {
                    McpSyncClient fresh = clientPool.clientFor(server);
                    return renderResultRich(fresh.callTool(new McpSchema.CallToolRequest(toolName, args)));
                } catch (Exception e) {
                    String msg = friendlyConnectError(e.getMessage() == null ? e.toString() : e.getMessage());
                    log.warn("mcp read-only callTool failed after reconnect: server={} tool={}: {}",
                            server.name(), toolName, shorten(msg));
                    return McpToolResult.error("ERROR: MCP 工具调用失败(" + toolName + "): " + shorten(msg));
                }
            }
            // 默认(或声明有副作用):保留结果未知,不自动重放
            log.warn("mcp callTool interrupted, outcome unknown (server={} tool={}): {}",
                    server.name(), toolName, shorten(raw));
            return McpToolResult.unknown("ERROR: MCP 工具调用中断(" + toolName + "): " + shorten(friendlyConnectError(raw))
                    + " —— 结果未知:该操作可能已在远端生效。不要直接重发;先查询/核对远端状态,确认未生效后再重试");
        }
    }

    /**
     * 工具快照里是否明确声明只读(readOnlyHint=true)。
     * 仅作参考信息(设计 §6):未声明/读取失败按「非只读」保守处理——
     * 未知能力的调用中断后不自动重放。
     */
    private boolean declaredReadOnly(long serverId, String toolName) {
        try {
            List<String> rows = jdbcTemplate.query(
                    "SELECT COALESCE(tools_cache, '') FROM mcp_server WHERE id = ? AND deleted_at IS NULL",
                    (rs, i) -> rs.getString(1), serverId);
            if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
                return false;
            }
            JsonNode root = objectMapper.readTree(rows.get(0));
            for (JsonNode t : root.path("tools")) {
                if (toolName.equals(t.path("name").asText())) {
                    return t.path("readOnlyHint").asBoolean(false);
                }
            }
        } catch (Exception e) {
            log.debug("readOnlyHint lookup failed for server {} tool {}: {}", serverId, toolName, e.getMessage());
        }
        return false;
    }

    /**
     * MCP 工具调用结果:文本渲染 + 保真的图片块。
     * images 为空 = 纯文本结果(与旧行为一致)。
     *
     * @param unknown 结果未知(调用已发出但中断,远端可能已生效)——与普通失败
     *                区分:模型与前端都不应把它当"没执行"处理(设计 §5.2)
     */
    public record McpToolResult(String text, boolean isError, List<ImageBlock> images, boolean unknown) {

        public McpToolResult {
            if (images == null) images = List.of();
        }

        /** 兼容构造:非 unknown 的结果(旧调用点)。 */
        public McpToolResult(String text, boolean isError, List<ImageBlock> images) {
            this(text, isError, images, false);
        }

        static McpToolResult error(String text) {
            return new McpToolResult(text, true, List.of(), false);
        }

        /** 调用中断、结果未知(有副作用且未声明只读,不自动重放)。 */
        static McpToolResult unknown(String text) {
            return new McpToolResult(text, true, List.of(), true);
        }

        /** 一个图片内容块(base64 原样,不做解码)。 */
        public record ImageBlock(String mimeType, String base64Data) {
        }
    }



    /** 服务关闭时关闭全部池化客户端 + 杀全部 stdio 进程。 */
    @PreDestroy
    public void shutdown() {
        clientPool.shutdown();
    }

    /**
     * 供编排层 toolsSpec 的聚合工具清单:启用的服务器且 tools_cache 非空,
     * 每个缓存工具一条,命名空间 {@code mcp__<server>__<tool>}。
     */
    public List<MountedTool> mountedTools() {
        List<MountedTool> out = new ArrayList<>();
        for (RawServer server : rawEnabled()) {
            // lazy 策略(2026-09-18 P2-9):不挂载为独立工具——其工具经
            // manage_mcp action=tools(查清单)/ action=call(按名调用)使用,
            // 把固定上下文成本从「全部工具」降到「服务器一行」。
            if ("lazy".equalsIgnoreCase(server.toolPolicy())) {
                continue;
            }
            String cache = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(tools_cache, '') FROM mcp_server WHERE id = ? AND deleted_at IS NULL", String.class, server.id());
            if (cache == null || cache.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(cache);
                for (JsonNode t : root.path("tools")) {
                    out.add(new MountedTool(server.id(), server.name(),
                            "mcp__" + server.name() + "__" + t.path("name").asText(),
                            t.path("name").asText(),
                            t.path("description").asText(""),
                            t.has("inputSchema") ? t.get("inputSchema") : null));
                }
            } catch (Exception e) {
                log.warn("mcp tools_cache parse failed for server {}: {}", server.id(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * 读取某服务器的工具缓存快照(供管理 UI 的工具列表/详情)。
     * 绝不触发远端调用——缓存由 {@link #refresh} 刷新;从未连接过时为空列表。
     *
     * @return 工具条目(name/description/inputSchema);服务器 id 未知时
     *         null(含软删)。
     */
    public List<ToolEntry> cachedTools(long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT COALESCE(tools_cache, '') FROM mcp_server WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getString(1), id);
        if (rows.isEmpty()) {
            return null;
        }
        String cache = rows.get(0);
        if (cache == null || cache.isBlank()) {
            return List.of();
        }
        List<ToolEntry> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(cache);
            for (JsonNode t : root.path("tools")) {
                out.add(new ToolEntry(t.path("name").asText(),
                        t.path("description").asText(""),
                        t.has("inputSchema") ? t.get("inputSchema") : null,
                        t.has("readOnlyHint") ? t.path("readOnlyHint").asBoolean() : null));
            }
        } catch (Exception e) {
            log.warn("mcp tools_cache parse failed for server {}: {}", id, e.getMessage());
            return List.of();
        }
        return out;
    }

    /** 把挂载(带命名空间)的工具名解析到其服务器行。 */
    public RawServer serverForMountedTool(String mountedName) {
        if (mountedName == null || !mountedName.startsWith("mcp__")) {
            return null;
        }
        // LLM 可能幻觉出畸形挂载名(如 mcp__srv 缺第二个 __):indexOf 为 -1 时
        // 直接返回 null 走 unknown-tool 兜底,不能让 substring 越界炸整轮对话
        int sep = mountedName.indexOf("__", "mcp__".length());
        if (sep < 0) {
            return null;
        }
        String serverName = mountedName.substring("mcp__".length(), sep);
        if (serverName.isEmpty()) {
            return null;
        }
        List<RawServer> rows = jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, tool_policy FROM mcp_server WHERE name = ? AND enabled = TRUE AND deleted_at IS NULL",
                (rs, i) -> withResolvedEnv(rawOf(rs)),
                serverName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 剥掉 {@code mcp__<server>__} 前缀,返回原始工具名。 */
    public static String rawToolName(String mountedName) {
        int first = mountedName.indexOf("__");
        int second = mountedName.indexOf("__", first + 2);
        return second < 0 ? mountedName : mountedName.substring(second + 2);
    }

    // ---------- 命令解析(stdio) ----------





    // ---------- 内部实现 ----------






    /**
     * 把内容块渲染为文本**并**保留图片块。
     *
     * <p>文本块原样透传。图片块追加一行短占位(纯文本模型也知道回来了视觉内容),
     * 同时把原始 base64 一并携带,供支持视觉的模型使用。
     * Unknown block types degrade to the previous "(非文本内容块: x)" line.
     */
    private McpToolResult renderResultRich(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        List<McpToolResult.ImageBlock> images = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    sb.append(text.text()).append('\n');
                } else if (content instanceof McpSchema.ImageContent image) {
                    // 图片块:文本侧留占位(避免整块 base64 进上下文),原图另存给视觉模型
                    int kb = image.data() == null ? 0 : image.data().length() * 3 / 4 / 1024;
                    sb.append("(图片内容块: ").append(image.mimeType() == null ? "image" : image.mimeType())
                            .append(", ").append(kb).append("KB)\n");
                    if (image.data() != null && !image.data().isBlank()) {
                        images.add(new McpToolResult.ImageBlock(
                                image.mimeType() == null ? "image/png" : image.mimeType(), image.data()));
                    }
                } else {
                    sb.append("(非文本内容块: ").append(content.type()).append(")\n");
                }
            }
        }
        boolean isError = result.isError() != null && result.isError();
        if (result.isError() != null && result.isError()) {
            return new McpToolResult("ERROR: " + sb.toString().strip(), true, images);
        }
        String out = sb.toString().stripTrailing();
        return new McpToolResult(out.isEmpty() ? "(工具无返回内容)" : out, isError, images);
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("值不是合法的键值对");
        }
    }

    private String writeJsonList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalArgumentException("args 不是合法的字符串数组");
        }
    }

    /** 脱敏机密值(headers / env),只留前几个字符。 */
    private String maskValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            ObjectNode masked = objectMapper.createObjectNode();
            root.fields().forEachRemaining(e -> {
                String v = e.getValue().asText("");
                masked.put(e.getKey(), v.length() <= 6 ? "••••••" : v.substring(0, 6) + "••••••");
            });
            return masked.toString();
        } catch (Exception e) {
            return "{ }";
        }
    }

    private Integer toolCount(String cache) {
        if (cache == null || cache.isBlank()) {
            return 0;
        }
        try {
            return objectMapper.readTree(cache).path("tools").size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shorten(String message) {
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }

    /**
     * 连接类错误的友好化(2026-09-19):SDK 的 "Client failed to initialize by
     * explicit API call" 对用户零信息量——手机/中继离线时就是这个消息。
     * 映射为可操作提示;非连接类错误原样保留(不掩盖真实原因)。
     */
    static String friendlyConnectError(String message) {
        if (message == null || message.isBlank()) {
            return "连接失败(无错误详情)";
        }
        if (message.contains("Client failed to initialize by explicit API call")) {
            return "无法建立连接——远程 MCP 服务器不可达(手机/中继离线,或地址与鉴权头失效)。"
                    + "请检查设备在线状态,稍后在 MCP 页「测试连接并刷新」重试";
        }
        if (message.contains("ConnectException") || message.contains("Connection refused")) {
            return "连接被拒绝——目标地址/端口未监听(服务未启动?)。原始错误: " + shorten(message);
        }
        if (message.contains("UnknownHostException")) {
            return "域名解析失败——地址拼写或 DNS 问题。原始错误: " + shorten(message);
        }
        if (message.contains("timed out") || message.contains("HttpTimeoutException")) {
            return "连接超时——目标不可达或网络不稳,稍后重试。原始错误: " + shorten(message);
        }
        return message;
    }

    private static final String VIEW_SELECT =
            "SELECT id, name, url, transport, headers, command, args, env, enabled, status, status_detail, tools_cache, tool_policy FROM mcp_server";

    private ServerView getByName(String name) {
        return queryOne(VIEW_SELECT + " WHERE name = ? AND deleted_at IS NULL", name);
    }

    /** 精确名称查找(公开:agent 工具注册时的重名检查)。 */
    public ServerView findByName(String name) {
        return name == null || name.isBlank() ? null : getByName(name.trim());
    }

    /** 共享的单行查找,返回脱敏视图。 */
    private ServerView queryOne(String sql, Object arg) {
        List<ServerView> rows = jdbcTemplate.query(sql, (rs, i) -> viewOf(rs), arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ServerView viewOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ServerView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("url"),
                rs.getString("transport"),
                rs.getString("command"),
                rs.getString("args"),
                maskValues(rs.getString("headers")),
                maskValues(rs.getString("env")),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getString("status_detail"),
                toolCount(rs.getString("tools_cache")),
                rs.getString("tool_policy"));
    }

    private RawServer rawOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RawServer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("url"),
                rs.getString("transport"),
                rs.getString("headers"),
                rs.getString("command"),
                rs.getString("args"),
                rs.getString("env"),
                rs.getBoolean("enabled"),
                rs.getString("tool_policy"));
    }

    // ---------- 记录类型 ----------

    /** 前端/设置中心消费的 MCP 服务器行。 */
    public record ServerView(
            long id,
            String name,
            String url,
            String transport,
            String command,
            String args,
            String maskedHeaders,
            String maskedEnv,
            boolean enabled,
            String status,
            String statusDetail,
            int toolCount,
            /** eager=工具直接挂载 / lazy=按需(manage_mcp action=tools|call)。 */
            String toolPolicy) {

        /** 兼容构造(2026-09-18 前调用点):缺省 eager。 */
        public ServerView(long id, String name, String url, String transport, String command, String args,
                          String maskedHeaders, String maskedEnv, boolean enabled, String status,
                          String statusDetail, int toolCount) {
            this(id, name, url, transport, command, args, maskedHeaders, maskedEnv, enabled, status,
                    statusDetail, toolCount, "eager");
        }
    }

    /** 连接建立用的原始行(机密未脱敏;绝不离开本服务)。 */
    public record RawServer(
            long id,
            String name,
            String url,
            String transport,
            String headers,
            String command,
            String args,
            String env,
            boolean enabled,
            String toolPolicy) {
    }

    /**
     * 缓存中的一个工具快照。
     *
     * @param readOnlyHint 远端 annotations.readOnlyHint(只读声明);null=未声明。
     *                     仅作参考:执行策略按本地规则(设计 §6「外部工具声明的
     *                     只读/幂等只能作为参考」),用于「有副作用调用断线后
     *                     不盲目重放」的判定——明确只读才允许自动重连重试。
     */
    public record ToolEntry(String name, String description, JsonNode inputSchema, Boolean readOnlyHint) {

        /** 兼容构造:无 annotations 的调用点(测试/旧数据)。 */
        public ToolEntry(String name, String description, JsonNode inputSchema) {
            this(name, description, inputSchema, null);
        }
    }

    /** 挂载进编排层 toolsSpec 的一个工具。 */
    public record MountedTool(
            long serverId,
            String serverName,
            String mountedName,
            String rawName,
            String description,
            JsonNode inputSchema) {
    }
}
