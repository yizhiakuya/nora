package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) server registry + client lifecycle for the
 * agent's dynamic tool mounting.
 *
 * <p>Design:
 * <ul>
 *   <li>Transports: STREAMABLE (HTTP) and SSE over JDK HttpClient; STDIO
 *       spawns a local child process (npx/node/docker ...) — stdin/stdout
 *       JSON-RPC. The command is preflight-resolved at register time (missing
 *       runtime → actionable error instead of a dead registration).</li>
 *   <li>Clients are connected lazily and pooled by server id; {@link #refresh}
 *       re-runs initialize + tools/list and snapshots the tool list into
 *       {@code tools_cache} (the orchestrator reads the cache, never blocks
 *       on a remote call during prompt assembly).</li>
 *   <li>Tool naming: {@code mcp__<server>__<tool>} — collision-free across
 *       servers and unambiguous to route back.</li>
 *   <li>Secrets: HTTP headers / stdio env values are stored in the DB, masked
 *       on read, raw only used when connecting.</li>
 *   <li>stdio process lifecycle: the child process tree is killed on
 *       evict/disable/delete/shutdown (Windows: taskkill /T; the npx.cmd →
 *       node grandchild would otherwise be orphaned).</li>
 * </ul>
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);


    private final JdbcTemplate jdbcTemplate;
    /** 客户端连接池 + STDIO 进程树(从本类拆出,2026-09-17)。 */
    private final McpClientPool clientPool;
    private final ObjectMapper objectMapper;

    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RelayMediaRouter relayRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clientPool = new McpClientPool(objectMapper, relayRouter);
    }

    // ---------- registry CRUD ----------

    /** Lists servers (secrets masked, no tools cache payload; soft-deleted excluded). */
    public List<ServerView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, status, status_detail, tools_cache FROM mcp_server WHERE deleted_at IS NULL ORDER BY id",
                (rs, i) -> viewOf(rs));
    }

    /**
     * Registers a server; returns the view with masked secrets.
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
        return getByName(name.trim());
    }

    /** Soft-deletes a server (name freed) and closes its pooled client (stdio: kills the process tree). */
    public boolean delete(long id) {
        clientPool.evictClient(id);
        return jdbcTemplate.update(
                "UPDATE mcp_server SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /**
     * Updates an existing remote server's url/headers in place (OAuth token
     * refresh path): the pooled client is evicted so the next connect uses
     * the new credentials. Returns the refreshed view, or null when unknown.
     */
    public ServerView updateRemoteCredentials(long id, String url, Map<String, String> headers) {
        clientPool.evictClient(id);
        int updated = jdbcTemplate.update(
                "UPDATE mcp_server SET url = COALESCE(?, url), headers = ?, status='untested', status_detail=NULL, tools_cache=NULL WHERE id = ? AND transport <> 'STDIO' AND deleted_at IS NULL",
                url == null || url.isBlank() ? null : url.trim(),
                headers == null || headers.isEmpty() ? null : writeJson(headers),
                id);
        return updated > 0 ? queryOne(VIEW_SELECT + " WHERE id = ? AND deleted_at IS NULL", id) : null;
    }

    /** Enables/disables a server; disabling also drops the pooled client. */
    public boolean setEnabled(long id, boolean enabled) {
        if (!enabled) {
            clientPool.evictClient(id);
        }
        return jdbcTemplate.update("UPDATE mcp_server SET status='untested', status_detail=NULL WHERE id = ? AND deleted_at IS NULL",
                id) >= 0
                && jdbcTemplate.update("UPDATE mcp_server SET enabled = ? WHERE id = ? AND deleted_at IS NULL", enabled, id) > 0;
    }

    /**
     * Resolves a server by numeric id or exact name (masked view).
     * Used by the agent's {@code manage_mcp} tool for enable/disable/
     * refresh/remove targets; null when not found.
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

    /** Fetches one raw row (secrets raw — internal use only; soft-deleted excluded). */
    public RawServer rawById(long id) {
        List<RawServer> rows = jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rawOf(rs),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Lists enabled servers' raw rows (internal: connection setup; soft-deleted excluded). */
    public List<RawServer> rawEnabled() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE enabled = TRUE AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> rawOf(rs));
    }

    // ---------- connect + tools ----------

    /**
     * Connects (or reuses) the client for a server, runs initialize +
     * tools/list, snapshots tools into {@code tools_cache} and updates
     * status. Returns the tool list.
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
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("mcp tools/list failed for server {} ({}): {}", id, server.name(), shorten(msg));
            jdbcTemplate.update("UPDATE mcp_server SET status='error', status_detail=? WHERE id = ?",
                    shorten(msg), id);
            throw new IllegalStateException("MCP 连接失败: " + shorten(msg));
        }
        List<ToolEntry> entries = new ArrayList<>();
        for (McpSchema.Tool tool : tools.tools()) {
            // SDK 的 inputSchema 可能是 POJO(JsonSchema);统一转 JsonNode 存快照
            entries.add(new ToolEntry(tool.name(), tool.description(),
                    tool.inputSchema() == null ? null : objectMapper.valueToTree(tool.inputSchema())));
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
        }
        jdbcTemplate.update("UPDATE mcp_server SET status='connected', status_detail=NULL, tools_cache=? WHERE id = ?",
                cache.toString(), id);
        return entries;
    }

    /**
     * Calls a tool on a server. The client is (re)connected on demand.
     *
     * @return LLM-friendly rendering of the tool result content
     */
    public String callTool(long serverId, String toolName, String argsJson) {
        return callToolRich(serverId, toolName, argsJson).text();
    }

    /**
     * Rich variant of {@link #callTool}: keeps image content blocks intact so the
     * orchestrator can feed them to vision-capable models as multimodal parts.
     * Text rendering stays the same as before (images leave a size placeholder).
     */
    public McpToolResult callToolRich(long serverId, String toolName, String argsJson) {
        RawServer server = rawById(serverId);
        if (server == null) {
            return McpToolResult.error("ERROR: mcp server " + serverId + " 不存在");
        }
        // 动态挂载的关键:远端服务器可能在中途挂掉/恢复,缓存的死连接必须能自愈——
        // 失败时把客户端踢出池(关闭)并重连重试一次;再失败才返回错误
        try {
            McpSyncClient client = clientPool.clientFor(server);
            return renderResultRich(client.callTool(new McpSchema.CallToolRequest(toolName,
                    argsJson == null || argsJson.isBlank()
                            ? Map.of()
                            : objectMapper.readValue(argsJson, Map.class))));
        } catch (Exception first) {
            log.info("mcp callTool failed (server={} tool={}), evicting pooled client and retrying once: {}",
                    server.name(), toolName, shorten(first.getMessage() == null ? first.toString() : first.getMessage()));
            clientPool.evictClient(serverId);
            try {
                McpSyncClient fresh = clientPool.clientFor(server);
                return renderResultRich(fresh.callTool(new McpSchema.CallToolRequest(toolName,
                        argsJson == null || argsJson.isBlank()
                                ? Map.of()
                                : objectMapper.readValue(argsJson, Map.class))));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("mcp callTool failed after reconnect: server={} tool={}: {}", server.name(), toolName, shorten(msg));
                return McpToolResult.error("ERROR: MCP 工具调用失败(" + toolName + "): " + shorten(msg));
            }
        }
    }

    /**
     * MCP 工具调用结果:文本渲染 + 保真的图片块。
     * images 为空 = 纯文本结果(与旧行为一致)。
     */
    public record McpToolResult(String text, boolean isError, List<ImageBlock> images) {

        public McpToolResult {
            if (images == null) images = List.of();
        }

        static McpToolResult error(String text) {
            return new McpToolResult(text, true, List.of());
        }

        /** 一个图片内容块(base64 原样,不做解码)。 */
        public record ImageBlock(String mimeType, String base64Data) {
        }
    }



    /** Closes all pooled clients + kills all stdio processes on service shutdown. */
    @PreDestroy
    public void shutdown() {
        clientPool.shutdown();
    }

    /**
     * Aggregated tool list for the orchestrator's toolsSpec: enabled servers
     * with a non-empty tools_cache, one entry per cached tool, namespaced
     * {@code mcp__<server>__<tool>}.
     */
    public List<MountedTool> mountedTools() {
        List<MountedTool> out = new ArrayList<>();
        for (RawServer server : rawEnabled()) {
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
     * Reads the cached tool snapshot of one server (for the admin UI's tool
     * list / detail view). Never triggers a remote call — the cache is
     * refreshed by {@link #refresh}; empty list when never connected.
     *
     * @return tool entries (name/description/inputSchema), or null when the
     *         server id is unknown (soft-deleted included).
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
                        t.has("inputSchema") ? t.get("inputSchema") : null));
            }
        } catch (Exception e) {
            log.warn("mcp tools_cache parse failed for server {}: {}", id, e.getMessage());
            return List.of();
        }
        return out;
    }

    /** Resolves a mounted (namespaced) tool name to its server row. */
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
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE name = ? AND enabled = TRUE AND deleted_at IS NULL",
                (rs, i) -> rawOf(rs),
                serverName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Strips the {@code mcp__<server>__} prefix, returning the raw tool name. */
    public static String rawToolName(String mountedName) {
        int first = mountedName.indexOf("__");
        int second = mountedName.indexOf("__", first + 2);
        return second < 0 ? mountedName : mountedName.substring(second + 2);
    }

    // ---------- command resolution (stdio) ----------





    // ---------- internals ----------






    /**
     * Renders content blocks into text **and** preserves image blocks.
     *
     * <p>Text blocks pass through as-is. Image blocks are appended as a short
     * placeholder line (so a text-only model still knows something visual came
     * back) while the raw base64 is carried alongside for vision-capable models.
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

    /** Mask secret values (headers / env), keeping only the first few chars. */
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

    private static final String VIEW_SELECT =
            "SELECT id, name, url, transport, headers, command, args, env, enabled, status, status_detail, tools_cache FROM mcp_server";

    private ServerView getByName(String name) {
        return queryOne(VIEW_SELECT + " WHERE name = ? AND deleted_at IS NULL", name);
    }

    /** Exact-name lookup (public: register duplicate check in the agent tool). */
    public ServerView findByName(String name) {
        return name == null || name.isBlank() ? null : getByName(name.trim());
    }

    /** Shared single-row lookup returning the masked view. */
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
                toolCount(rs.getString("tools_cache")));
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
                rs.getBoolean("enabled"));
    }

    // ---------- records ----------

    /** MCP server row as consumed by the frontend/settings center. */
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
            int toolCount) {
    }

    /** Raw row for connection setup (secrets unmasked; never leaves the service). */
    public record RawServer(
            long id,
            String name,
            String url,
            String transport,
            String headers,
            String command,
            String args,
            String env,
            boolean enabled) {
    }

    /** One tool snapshot in the cache. */
    public record ToolEntry(String name, String description, JsonNode inputSchema) {
    }

    /** A tool mounted into the orchestrator's toolsSpec. */
    public record MountedTool(
            long serverId,
            String serverName,
            String mountedName,
            String rawName,
            String description,
            JsonNode inputSchema) {
    }
}
