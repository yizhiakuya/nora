package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) server registry + client lifecycle for the
 * agent's dynamic tool mounting.
 *
 * <p>Design (v1):
 * <ul>
 *   <li>Transports: STREAMABLE (HTTP) and SSE, both over JDK HttpClient —
 *       no stdio (agent-service is a server-side singleton; spawning child
 *       processes per server is an ops model this deployment doesn't use).</li>
 *   <li>Clients are connected lazily and pooled by server id; {@link #refresh}
 *       re-runs initialize + tools/list and snapshots the tool list into
 *       {@code tools_cache} (the orchestrator reads the cache, never blocks
 *       on a remote call during prompt assembly).</li>
 *   <li>Tool naming: {@code mcp__<server>__<tool>} — collision-free across
 *       servers and unambiguous to route back.</li>
 *   <li>Headers (auth) are stored in the DB, masked on read, raw only used
 *       when connecting.</li>
 * </ul>
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    /** server id → live client (connected lazily, removed on delete/disable) */
    private final Map<Long, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------- registry CRUD ----------

    /** Lists servers (headers masked, no tools cache payload). */
    public List<ServerView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, enabled, status, status_detail, tools_cache FROM mcp_server ORDER BY id",
                (rs, i) -> new ServerView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("url"),
                        rs.getString("transport"),
                        maskHeaders(rs.getString("headers")),
                        rs.getBoolean("enabled"),
                        rs.getString("status"),
                        rs.getString("status_detail"),
                        toolCount(rs.getString("tools_cache"))));
    }

    /** Registers a server; returns the view with masked headers. */
    public ServerView create(String name, String url, String transport, Map<String, String> headers) {
        String t = transport == null || transport.isBlank() ? "STREAMABLE" : transport.trim().toUpperCase();
        if (!List.of("STREAMABLE", "SSE").contains(t)) {
            throw new IllegalArgumentException("transport 只支持 STREAMABLE / SSE");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (url == null || !url.startsWith("http")) {
            throw new IllegalArgumentException("url 必须是 http(s) 地址");
        }
        // 服务器名进入挂载工具名 mcp__<server>__<tool>:上游 OpenAI 兼容 API 对
        // function name 有 ^[a-zA-Z0-9_-]+$ 约束,且含 "__" 会让挂载名解析
        // (取第二个 __ 前的段)错位——注册时就拒绝,别等每轮对话 400
        if (!name.trim().matches("^[a-zA-Z0-9_-]+$") || name.trim().contains("__")) {
            throw new IllegalArgumentException("服务器名只能包含字母、数字、下划线、连字符,且不能含连续下划线");
        }
        jdbcTemplate.update(
                "INSERT INTO mcp_server (name, url, transport, headers) VALUES (?, ?, ?, ?)",
                name.trim(), url.trim(), t, headers == null || headers.isEmpty() ? null : writeJson(headers));
        return getByName(name.trim());
    }

    /** Deletes a server and closes its pooled client. */
    public boolean delete(long id) {
        McpSyncClient client = clients.remove(id);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("mcp client close failed for server {}: {}", id, e.getMessage());
            }
        }
        return jdbcTemplate.update("DELETE FROM mcp_server WHERE id = ?", id) > 0;
    }

    /** Enables/disables a server; disabling also drops the pooled client. */
    public boolean setEnabled(long id, boolean enabled) {
        if (!enabled) {
            McpSyncClient client = clients.remove(id);
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("mcp client close failed for server {}: {}", id, e.getMessage());
                }
            }
        }
        return jdbcTemplate.update("UPDATE mcp_server SET status='untested', status_detail=NULL WHERE id = ?",
                id) >= 0
                && jdbcTemplate.update("UPDATE mcp_server SET enabled = ? WHERE id = ?", enabled, id) > 0;
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
            ServerView byId = queryOne("SELECT id, name, url, transport, headers, enabled, status, status_detail, tools_cache"
                    + " FROM mcp_server WHERE id = ?", Long.parseLong(t));
            if (byId != null) {
                return byId;
            }
        }
        return getByName(t);
    }

    /** Fetches one raw row (headers raw — internal use only). */
    public RawServer rawById(long id) {
        List<RawServer> rows = jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, enabled FROM mcp_server WHERE id = ?",
                (rs, i) -> new RawServer(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("transport"), rs.getString("headers"), rs.getBoolean("enabled")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Lists enabled servers' raw rows (internal: connection setup). */
    public List<RawServer> rawEnabled() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, enabled FROM mcp_server WHERE enabled = TRUE ORDER BY id",
                (rs, i) -> new RawServer(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("transport"), rs.getString("headers"), rs.getBoolean("enabled")));
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
            McpSyncClient client = clientFor(server);
            tools = client.listTools();
        } catch (Exception e) {
            // 失败即驱逐池中客户端(可能是 initialize 成功但 listTools 抖动的
            // 半死连接):与 callTool 的自愈语义一致,下次 refresh 用全新连接
            evictClient(id);
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
        RawServer server = rawById(serverId);
        if (server == null) {
            return "ERROR: mcp server " + serverId + " 不存在";
        }
        // 动态挂载的关键:远端服务器可能在中途挂掉/恢复,缓存的死连接必须能自愈——
        // 失败时把客户端踢出池(关闭)并重连重试一次;再失败才返回错误
        try {
            McpSyncClient client = clientFor(server);
            return renderResult(client.callTool(new McpSchema.CallToolRequest(toolName,
                    argsJson == null || argsJson.isBlank()
                            ? Map.of()
                            : objectMapper.readValue(argsJson, Map.class))));
        } catch (Exception first) {
            log.info("mcp callTool failed (server={} tool={}), evicting pooled client and retrying once: {}",
                    server.name(), toolName, shorten(first.getMessage() == null ? first.toString() : first.getMessage()));
            evictClient(serverId);
            try {
                McpSyncClient fresh = clientFor(server);
                return renderResult(fresh.callTool(new McpSchema.CallToolRequest(toolName,
                        argsJson == null || argsJson.isBlank()
                                ? Map.of()
                                : objectMapper.readValue(argsJson, Map.class))));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("mcp callTool failed after reconnect: server={} tool={}: {}", server.name(), toolName, shorten(msg));
                return "ERROR: MCP 工具调用失败(" + toolName + "): " + shorten(msg);
            }
        }
    }

    /** Closes and removes the pooled client for a server (self-heal on failure). */
    private void evictClient(long serverId) {
        McpSyncClient stale = clients.remove(serverId);
        if (stale != null) {
            try {
                stale.close();
            } catch (Exception e) {
                log.debug("mcp stale client close failed for server {}: {}", serverId, e.getMessage());
            }
        }
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
                    "SELECT COALESCE(tools_cache, '') FROM mcp_server WHERE id = ?", String.class, server.id());
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
                "SELECT id, name, url, transport, headers, enabled FROM mcp_server WHERE name = ? AND enabled = TRUE",
                (rs, i) -> new RawServer(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("transport"), rs.getString("headers"), rs.getBoolean("enabled")),
                serverName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Strips the {@code mcp__<server>__} prefix, returning the raw tool name. */
    public static String rawToolName(String mountedName) {
        int first = mountedName.indexOf("__");
        int second = mountedName.indexOf("__", first + 2);
        return second < 0 ? mountedName : mountedName.substring(second + 2);
    }

    // ---------- internals ----------

    /** Builds (or reuses) a connected client for the server. */
    private McpSyncClient clientFor(RawServer server) {
        McpSyncClient existing = clients.get(server.id());
        if (existing != null) {
            return existing;
        }
        // 注册时收集的鉴权头(Authorization 等)必须真正带上——SDK 2.x 没有
        // headers builder API,统一走 httpRequestCustomizer 在每个请求上注入
        java.util.Map<String, String> headers = parseHeaders(server.headers());
        io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
                headerInjector = (requestBuilder, method, uri, requestBody, context) -> {
            headers.forEach(requestBuilder::header);
        };
        McpClientTransport transport = switch (server.transport() == null ? "STREAMABLE" : server.transport()) {
            case "SSE" ->
                io.modelcontextprotocol.client.transport.HttpClientSseClientTransport.builder(server.url())
                        .httpRequestCustomizer(headerInjector)
                        .build();
            default ->
                io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(server.url())
                        .httpRequestCustomizer(headerInjector)
                        .build();
        };
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .clientInfo(new McpSchema.Implementation("nora-agent", "1.0"))
                .build();
        client.initialize();
        clients.put(server.id(), client);
        return client;
    }

    /** Parses the headers JSON column; malformed JSON → empty map(不阻断连接). */
    private java.util.Map<String, String> parseHeaders(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            root.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            return out;
        } catch (Exception e) {
            log.warn("mcp headers parse failed for server: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Renders CallToolResult content blocks into a single text payload. */
    private String renderResult(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    sb.append(text.text()).append('\n');
                } else {
                    sb.append("(非文本内容块: ").append(content.type()).append(")\n");
                }
            }
        }
        if (result.isError() != null && result.isError()) {
            return "ERROR: " + sb.toString().strip();
        }
        String out = sb.toString().stripTrailing();
        return out.isEmpty() ? "(工具无返回内容)" : out;
    }

    private String writeJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalArgumentException("headers 不是合法的键值对");
        }
    }

    /** Mask header values, keeping only the scheme prefix of auth tokens. */
    private String maskHeaders(String raw) {
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

    private ServerView getByName(String name) {
        return queryOne("SELECT id, name, url, transport, headers, enabled, status, status_detail, tools_cache"
                + " FROM mcp_server WHERE name = ?", name);
    }

    /** Exact-name lookup (public: register duplicate check in the agent tool). */
    public ServerView findByName(String name) {
        return name == null || name.isBlank() ? null : getByName(name.trim());
    }

    /** Shared single-row lookup returning the masked view. */
    private ServerView queryOne(String sql, Object arg) {
        List<ServerView> rows = jdbcTemplate.query(sql,
                (rs, i) -> new ServerView(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("transport"), maskHeaders(rs.getString("headers")),
                        rs.getBoolean("enabled"), rs.getString("status"), rs.getString("status_detail"),
                        toolCount(rs.getString("tools_cache"))),
                arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- records ----------

    /** MCP server row as consumed by the frontend/settings center. */
    public record ServerView(
            long id,
            String name,
            String url,
            String transport,
            String maskedHeaders,
            boolean enabled,
            String status,
            String statusDetail,
            int toolCount) {
    }

    /** Raw row for connection setup (headers unmasked; never leaves the service). */
    public record RawServer(
            long id,
            String name,
            String url,
            String transport,
            String headers,
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
