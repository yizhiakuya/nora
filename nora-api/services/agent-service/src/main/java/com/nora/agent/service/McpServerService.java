package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /** stdio 首轮可能触发 npx 下载包(30s+),请求超时放宽;HTTP 保持 60s。 */
    private static final Duration STDIO_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    /** server id → live client (connected lazily, removed on delete/disable) */
    private final Map<Long, McpSyncClient> clients = new ConcurrentHashMap<>();
    /** server id → spawned stdio child process (killed on evict/disable/delete) */
    private final Map<Long, Process> stdioProcs = new ConcurrentHashMap<>();
    /**
     * server id → process-tree snapshot taken at connect time (root + all
     * descendants). 进程被中途"截断"(父进程先死)会从子孙链上消失,
     * 杀树时再查询 descendants() 已看不到它们——快照句柄不依赖父子链,
     * 是清理孤儿进程的最后防线(实测 npx 三层树:cmd → node-cli → node-server)。
     */
    private final Map<Long, List<ProcessHandle>> stdioTrees = new ConcurrentHashMap<>();

    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------- registry CRUD ----------

    /** Lists servers (secrets masked, no tools cache payload). */
    public List<ServerView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled, status, status_detail, tools_cache FROM mcp_server ORDER BY id",
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
            String resolved = resolveCommand(command.trim());
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

    /** Deletes a server and closes its pooled client (stdio: kills the process tree). */
    public boolean delete(long id) {
        evictClient(id);
        return jdbcTemplate.update("DELETE FROM mcp_server WHERE id = ?", id) > 0;
    }

    /**
     * Updates an existing remote server's url/headers in place (OAuth token
     * refresh path): the pooled client is evicted so the next connect uses
     * the new credentials. Returns the refreshed view, or null when unknown.
     */
    public ServerView updateRemoteCredentials(long id, String url, Map<String, String> headers) {
        evictClient(id);
        int updated = jdbcTemplate.update(
                "UPDATE mcp_server SET url = COALESCE(?, url), headers = ?, status='untested', status_detail=NULL, tools_cache=NULL WHERE id = ? AND transport <> 'STDIO'",
                url == null || url.isBlank() ? null : url.trim(),
                headers == null || headers.isEmpty() ? null : writeJson(headers),
                id);
        return updated > 0 ? queryOne(VIEW_SELECT + " WHERE id = ?", id) : null;
    }

    /** Enables/disables a server; disabling also drops the pooled client. */
    public boolean setEnabled(long id, boolean enabled) {
        if (!enabled) {
            evictClient(id);
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
            ServerView byId = queryOne(VIEW_SELECT + " WHERE id = ?", Long.parseLong(t));
            if (byId != null) {
                return byId;
            }
        }
        return getByName(t);
    }

    /** Fetches one raw row (secrets raw — internal use only). */
    public RawServer rawById(long id) {
        List<RawServer> rows = jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE id = ?",
                (rs, i) -> rawOf(rs),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Lists enabled servers' raw rows (internal: connection setup). */
    public List<RawServer> rawEnabled() {
        return jdbcTemplate.query(
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE enabled = TRUE ORDER BY id",
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

    /** Closes the pooled client and kills the stdio process tree (self-heal on failure). */
    private void evictClient(long serverId) {
        // stdio 必须"先杀进程树、后关客户端":close() 会让直接子进程(cmd.exe
        // 包装层)先退出,其孙进程(node)随即被孤儿化——之后再 taskkill /T
        // 已找不到树,node 会一直残留(实测踩坑)。
        killProcessTree(serverId);
        McpSyncClient stale = clients.remove(serverId);
        if (stale != null) {
            try {
                stale.close();
            } catch (Exception e) {
                log.debug("mcp stale client close failed for server {}: {}", serverId, e.getMessage());
            }
        }
    }

    /** Kills the spawned stdio process tree for a server (no-op for HTTP transports). */
    private void killProcessTree(long serverId) {
        Process proc = stdioProcs.remove(serverId);
        // 快照兜底:即使根进程已死/树被截断,连接时记录的全部句柄仍可逐个强杀
        List<ProcessHandle> snapshot = stdioTrees.remove(serverId);
        if (proc == null && (snapshot == null || snapshot.isEmpty())) {
            return;
        }
        try {
            // 先捕获当前后代句柄:taskkill /T 覆盖当前树,但被孤儿化的
            // 孙进程需要按句柄逐个兜底强杀
            List<ProcessHandle> descendants = proc != null && proc.isAlive()
                    ? proc.descendants().toList() : List.of();
            if (proc != null && isWindows() && proc.isAlive()) {
                // npx.cmd 的孙进程(node)不在 Process.destroy 覆盖范围内,
                // 必须用 taskkill /T 杀整棵树,否则孤儿进程继续占着 stdin/stdout
                Process killer = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(proc.pid()))
                        .redirectErrorStream(true).start();
                killer.waitFor(5, TimeUnit.SECONDS);
            }
            for (ProcessHandle h : descendants) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
            // 快照句柄:覆盖被截断/孤儿化的进程(不依赖父子链)
            if (snapshot != null) {
                for (ProcessHandle h : snapshot) {
                    if (h.isAlive()) {
                        h.destroyForcibly();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("mcp stdio tree kill failed for server {}: {}", serverId, e.getMessage());
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
    }

    /** Closes all pooled clients + kills all stdio processes on service shutdown. */
    @PreDestroy
    public void shutdown() {
        for (Long id : List.copyOf(clients.keySet())) {
            evictClient(id);
        }
        // 两个 map 任一有残留都清理(killProcessTree 同时移除两者)
        java.util.Set<Long> remaining = new java.util.HashSet<>(stdioProcs.keySet());
        remaining.addAll(stdioTrees.keySet());
        for (Long id : remaining) {
            killProcessTree(id);
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
                "SELECT id, name, url, transport, headers, command, args, env, enabled FROM mcp_server WHERE name = ? AND enabled = TRUE",
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

    /**
     * Resolves a command to an executable path: absolute path → exists check;
     * bare name → PATH scan (Windows: PATHEXT candidates first — CreateProcess
     * does not resolve the extensionless shell shim `npx`, only `npx.cmd`).
     * Returns null when not found (callers surface an actionable error).
     */
    static String resolveCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String c = command.trim();
        File direct = new File(c);
        if (direct.isAbsolute()) {
            if (direct.isFile()) {
                return direct.getPath();
            }
            // 绝对路径但没带扩展名(Windows):按 PATHEXT 补全
            if (isWindows() && !hasExtension(c)) {
                for (String ext : pathExtCandidates()) {
                    File f = new File(c + ext);
                    if (f.isFile()) {
                        return f.getPath();
                    }
                }
            }
            return null;
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (isWindows() && !hasExtension(c)) {
            for (String ext : pathExtCandidates()) {
                names.add(c + ext);
            }
        } else {
            names.add(c);
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                File f = new File(dir, name);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean hasExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return name.indexOf('.', slash + 1) >= 0;
    }

    private static List<String> pathExtCandidates() {
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }
        List<String> out = new ArrayList<>();
        for (String ext : pathext.split(";")) {
            if (!ext.isBlank()) {
                out.add(ext.trim().toLowerCase());
            }
        }
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ---------- internals ----------

    /** Builds (or reuses) a connected client for the server. */
    private McpSyncClient clientFor(RawServer server) {
        McpSyncClient existing = clients.get(server.id());
        if (existing != null) {
            return existing;
        }
        String transportName = server.transport() == null ? "STREAMABLE" : server.transport();
        McpClientTransport transport;
        Duration requestTimeout;
        if ("STDIO".equals(transportName)) {
            String resolved = resolveCommand(server.command());
            if (resolved == null) {
                throw new IllegalStateException("命令未找到:「" + server.command() + "」——请确认本机已安装对应运行时"
                        + "(Node 包需 Node.js;Python 包需 uv/uvx),或用 Docker 方式(docker run -i --rm 镜像名)");
            }
            io.modelcontextprotocol.client.transport.ServerParameters.Builder params =
                    io.modelcontextprotocol.client.transport.ServerParameters.builder(resolved);
            List<String> args = parseStringList(server.args());
            if (!args.isEmpty()) {
                params.args(args);
            }
            Map<String, String> env = parseHeaders(server.env());
            if (!env.isEmpty()) {
                params.env(env);
            }
            io.modelcontextprotocol.client.transport.StdioClientTransport stdio =
                    new io.modelcontextprotocol.client.transport.StdioClientTransport(
                            params.build(), new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier().get());
            // stderr 必须持续排空(否则管道写满会死锁),内容进日志便于排障
            stdio.setStdErrorHandler(line -> log.debug("mcp stdio [{}] stderr: {}", server.name(), line));
            transport = stdio;
            requestTimeout = STDIO_REQUEST_TIMEOUT;
        } else {
            // 注册时收集的鉴权头(Authorization 等)必须真正带上——SDK 2.x 没有
            // headers builder API,统一走 httpRequestCustomizer 在每个请求上注入
            Map<String, String> headers = parseHeaders(server.headers());
            io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
                    headerInjector = (requestBuilder, method, uri, requestBody, context) -> {
                headers.forEach(requestBuilder::header);
            };
            transport = switch (transportName) {
                case "SSE" ->
                    io.modelcontextprotocol.client.transport.HttpClientSseClientTransport.builder(server.url())
                            .httpRequestCustomizer(headerInjector)
                            .build();
                default ->
                    io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(server.url())
                            .httpRequestCustomizer(headerInjector)
                            .build();
            };
            requestTimeout = HTTP_REQUEST_TIMEOUT;
        }
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .clientInfo(new McpSchema.Implementation("nora-agent", "1.0"))
                .build();
        client.initialize();
        if ("STDIO".equals(transportName)) {
            // 进程句柄用于进程树清理(disable/delete/shutdown);反射失败不阻断功能
            Process proc = extractProcess(transport);
            if (proc != null) {
                stdioProcs.put(server.id(), proc);
                // 全树快照:initialize 完成后 npx 的包装层(cmd → node-cli → node-server)
                // 已就位,此刻记录全部句柄——之后任何一层先死,清理仍能兜底杀掉全部残留
                List<ProcessHandle> tree = new ArrayList<>();
                tree.add(proc.toHandle());
                proc.descendants().forEach(tree::add);
                stdioTrees.put(server.id(), tree);
            }
        }
        clients.put(server.id(), client);
        return client;
    }

    /** Reads the child process handle off StdioClientTransport (private field, best-effort). */
    private Process extractProcess(McpClientTransport transport) {
        try {
            java.lang.reflect.Field f = transport.getClass().getDeclaredField("process");
            f.setAccessible(true);
            Object proc = f.get(transport);
            return proc instanceof Process p ? p : null;
        } catch (Exception e) {
            log.warn("mcp stdio process handle unavailable (reflection): {}", e.getMessage());
            return null;
        }
    }

    /** Parses a JSON string-array column; malformed → empty list(不阻断连接). */
    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<String> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    out.add(n.asText(""));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("mcp args parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Parses a JSON string-map column (headers / env); malformed → empty map(不阻断连接). */
    private Map<String, String> parseHeaders(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            Map<String, String> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            return out;
        } catch (Exception e) {
            log.warn("mcp headers/env parse failed: {}", e.getMessage());
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
        return queryOne(VIEW_SELECT + " WHERE name = ?", name);
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
