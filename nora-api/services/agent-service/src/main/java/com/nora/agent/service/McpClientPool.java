package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 客户端连接池(2026-09-17 从 McpServerService 拆出,复杂度审计建议 #2):
 * 按 server id 懒连接 + 池化;STDIO 子进程树的生命周期管理
 * (evict/disable/delete/shutdown 时杀树,快照句柄兜底孤儿进程)。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class McpClientPool {

    private static final Logger log = LoggerFactory.getLogger(McpClientPool.class);

    /** stdio 首轮可能触发 npx 下载包(30s+),请求超时放宽;HTTP 保持 60s。 */
    private static final Duration STDIO_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(60);

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

    McpClientPool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Builds (or reuses) a connected client for the server. */
    McpSyncClient clientFor(McpServerService.RawServer server) {
        McpSyncClient existing = clients.get(server.id());
        if (existing != null) {
            return existing;
        }
        String transportName = server.transport() == null ? "STREAMABLE" : server.transport();
        McpClientTransport transport;
        Duration requestTimeout;
        if ("STDIO".equals(transportName)) {
            String resolved = McpCommandResolver.resolveCommand(server.command());
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

    /** Closes the pooled client and kills the stdio process tree (self-heal on failure). */
    void evictClient(long serverId) {
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
            if (proc != null && McpCommandResolver.isWindows() && proc.isAlive()) {
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

    /** Closes all pooled clients + kills all stdio processes (service shutdown 委托)。 */
    void shutdown() {
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
}