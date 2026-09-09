package com.nora.env.controller;

import com.nora.common.response.ApiResponse;
import com.nora.env.service.DockerClientService;
import com.nora.env.service.ManagedSourceService;
import com.nora.env.service.ProcessSupervisorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 环境控制台端点:纳管清单 CRUD、服务/日志源列表、容器启停、日志 tail 与
 * SSE 流(FILE 文件 + DOCKER 容器双支持)、AI 日志分析(只读,ASSIST 档)。
 */
@RestController
@RequestMapping("/api/environment")
public class ServiceController {

    private final DockerClientService docker;
    private final ManagedSourceService managed;
    private final ProcessSupervisorService supervisor;
    private final RestClient agentClient;
    private final ExecutorService logExecutor = Executors.newCachedThreadPool();

    public ServiceController(DockerClientService docker, ManagedSourceService managed,
                             ProcessSupervisorService supervisor,
                             @Value("${nora.agent.base-url:http://localhost:8083}") String agentBaseUrl) {
        this.docker = docker;
        this.managed = managed;
        this.supervisor = supervisor;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(180_000); // agent 分析覆盖 RAG + 工具循环
        this.agentClient = RestClient.builder().baseUrl(agentBaseUrl).requestFactory(factory).build();
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * 纳管源列表(FILE + DOCKER 合一)。DOCKER 源合并运行时容器状态;
     * FILE 源附日志文件最近修改时间作活性信号。
     */
    @GetMapping("/services")
    public ApiResponse<List<Map<String, Object>>> services() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, DockerClientService.ContainerView> containers = new java.util.HashMap<>();
        for (DockerClientService.ContainerView c : docker.list()) {
            containers.put(c.name(), c);
        }
        // 一次性拉全部容器即时 CPU/内存(docker stats --no-stream,约 1-2s),
        // 30s 轮询频率下可接受;stats 失败时保持 "—"
        Map<String, String[]> stats = docker.stats();
        for (ManagedSourceService.SourceView s : managed.list()) {
            if (!s.enabled()) {
                continue; // 暂停的源不下发(保留记录,恢复后回来)
            }
            if ("DOCKER".equals(s.kind())) {
                DockerClientService.ContainerView c = containers.get(s.containerName());
                if (c == null) {
                    // 容器不存在(被删/未启动 docker):报 error 而不是静默消失
                    out.add(new java.util.LinkedHashMap<>(Map.of(
                            "id", String.valueOf(s.id()),
                            "name", s.name(),
                            "kind", "DOCKER",
                            "containerName", s.containerName(),
                            "status", "error",
                            "health", "down",
                            "detail", "容器不存在或 docker 不可用")));
                    continue;
                }
                String[] res = stats.get(s.containerName());
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", String.valueOf(s.id()));
                item.put("name", s.name());
                item.put("kind", "DOCKER");
                item.put("containerName", s.containerName());
                item.put("image", c.image());
                item.put("status", c.status());
                item.put("health", c.health());
                item.put("port", c.port());
                item.put("uptime", c.uptime());
                item.put("cpu", res == null ? c.cpu() : res[0]);
                item.put("memory", res == null ? c.memory() : res[1]);
                out.add(item);
            } else if ("PROC".equals(s.kind())) {
                // 平台拉起的本地程序:进程守护合并运行时状态,日志走受管文件(FILE 链路)
                ProcessSupervisorService.ProcStatus ps = supervisor.status(s.id());
                String health = ps.alive() ? "healthy" : "down";
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", String.valueOf(s.id()));
                item.put("name", s.name());
                item.put("kind", "PROC");
                item.put("command", s.command() == null ? "" : s.command());
                item.put("workDir", s.workDir() == null ? "" : s.workDir());
                item.put("status", ps.alive() ? "running" : "stopped");
                item.put("health", health);
                item.put("detail", ps.alive()
                        ? "运行中 · pid " + ps.pid()
                        : ps.lastExitCode() != null
                                ? "已停止 · 上次退出码 " + ps.lastExitCode()
                                : "已停止");
                item.put("uptime", ps.alive() && ps.uptime() != null ? ps.uptime() : "—");
                if (ps.memoryBytes() != null) {
                    item.put("memory", humanBytes(ps.memoryBytes()));
                }
                out.add(item);
            } else {
                // FILE 源:按日志文件 mtime 分级活性——5 分钟内健康,30 分钟内降级,更久/不存在视为失联
                Long ageMin = fileAgeMinutes(s.fileLogPath());
                boolean exists = ageMin != null;
                String health = !exists ? "down" : ageMin <= 5 ? "healthy" : ageMin <= 30 ? "degraded" : "down";
                out.add(new java.util.LinkedHashMap<>(Map.of(
                        "id", String.valueOf(s.id()),
                        "name", s.name(),
                        "kind", "FILE",
                        "fileLogPath", s.fileLogPath(),
                        "status", exists ? "running" : "error",
                        "health", health,
                        "detail", !exists ? "日志文件不存在" : "日志活跃于 " + humanAge(ageMin))));
            }
        }
        return ApiResponse.ok(out);
    }

    /** 日志文件距今的分钟数;文件不存在/不可读返回 null。 */
    private Long fileAgeMinutes(String path) {
        try {
            long ageMs = System.currentTimeMillis() - new java.io.File(path).lastModified();
            return ageMs / 60_000;
        } catch (Exception e) {
            return null;
        }
    }

    /** 分钟数 → 友好描述。 */
    private String humanAge(long min) {
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        return h + " 小时前";
    }

    // ---------- 纳管清单 CRUD ----------

    /** 全部纳管源(含暂停的;/services 只返回启用的)。 */
    @GetMapping("/managed")
    public ApiResponse<List<ManagedSourceService.SourceView>> listManaged() {
        return ApiResponse.ok(managed.list());
    }

    /** 添加纳管源。body: {kind, name, fileLogPath?, containerName?, command?, workDir?} */
    @PostMapping("/managed")
    public ApiResponse<ManagedSourceService.SourceView> addManaged(@RequestBody Map<String, String> body) {
        ManagedSourceService.SourceView view = managed.create(
                body.get("kind"), body.get("name"),
                body.get("fileLogPath"), body.get("containerName"),
                body.get("command"), body.get("workDir"));
        return ApiResponse.ok(view);
    }

    /** 删除纳管源(不动容器/文件本身)。 */
    @DeleteMapping("/managed/{id}")
    public ApiResponse<Boolean> deleteManaged(@PathVariable long id) {
        return ApiResponse.ok(managed.delete(id));
    }

    /** 暂停/恢复纳管。body: {enabled} */
    @PostMapping("/managed/{id}/enabled")
    public ApiResponse<Boolean> toggleManaged(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(managed.setEnabled(id, Boolean.TRUE.equals(body.get("enabled"))));
    }

    /** PROC 守护事件(死亡/自愈/启动失败),前端通知中心增量消费。 */
    @GetMapping("/proc-events")
    public ApiResponse<List<Map<String, Object>>> procEvents(
            @RequestParam(value = "after", required = false) String after) {
        Instant cursor = after == null || after.isBlank() ? null : Instant.parse(after);
        return ApiResponse.ok(supervisor.eventsAfter(cursor));
    }

    // ---------- 容器操作(DOCKER 源) ----------

    /** Starts a container (by container name) or a managed process (by name). */
    @PostMapping("/services/{name}/start")
    public ApiResponse<StatusView> start(@PathVariable String name) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.name().equals(name) && x.enabled()).findFirst().orElse(null);
        if (s != null && "PROC".equals(s.kind())) {
            try {
                ProcessSupervisorService.ProcStatus ps = supervisor.start(s.id());
                return ApiResponse.ok(new StatusView(ps.alive() ? "running" : "error",
                        ps.alive() ? "已启动 · pid " + ps.pid() : "启动失败"));
            } catch (Exception e) {
                return ApiResponse.ok(new StatusView("error", "ERROR: " + e.getMessage()));
            }
        }
        String result = docker.start(name);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "running", result));
    }

    /** Stops a container or a managed process. */
    @PostMapping("/services/{name}/stop")
    public ApiResponse<StatusView> stop(@PathVariable String name) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.name().equals(name) && x.enabled()).findFirst().orElse(null);
        if (s != null && "PROC".equals(s.kind())) {
            try {
                ProcessSupervisorService.ProcStatus ps = supervisor.stop(s.id());
                return ApiResponse.ok(new StatusView(ps.alive() ? "error" : "stopped",
                        ps.alive() ? "停止失败,进程仍存活" : "已停止"));
            } catch (Exception e) {
                return ApiResponse.ok(new StatusView("error", "ERROR: " + e.getMessage()));
            }
        }
        String result = docker.stop(name);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "stopped", result));
    }

    /** Restarts a container or a managed process. */
    @PostMapping("/services/{name}/restart")
    public ApiResponse<StatusView> restart(@PathVariable String name) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.name().equals(name) && x.enabled()).findFirst().orElse(null);
        if (s != null && "PROC".equals(s.kind())) {
            try {
                ProcessSupervisorService.ProcStatus ps = supervisor.restart(s.id());
                return ApiResponse.ok(new StatusView(ps.alive() ? "running" : "error",
                        ps.alive() ? "已重启 · pid " + ps.pid() : "重启失败"));
            } catch (Exception e) {
                return ApiResponse.ok(new StatusView("error", "ERROR: " + e.getMessage()));
            }
        }
        String result = docker.restart(name);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "running", result));
    }

    // ---------- 日志(FILE tail + DOCKER logs,统一形状) ----------

    /**
     * 日志 tail(非流式):按纳管源 id 读最近 N 行。FILE 源读日志文件,
     * DOCKER 源走 docker logs。agent 工具路径用它拿有界即时答案。
     */
    @GetMapping("/sources/{id}/logs")
    public ApiResponse<List<String>> sourceLogs(@PathVariable long id,
                                                @RequestParam(value = "tail", defaultValue = "200") int tail) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.id() == id).findFirst().orElse(null);
        if (s == null) {
            throw new IllegalArgumentException("纳管源不存在: " + id);
        }
        int bounded = Math.min(Math.max(tail, 1), 500);
        try {
            List<String> lines;
            if ("FILE".equals(s.kind())) {
                lines = managed.tailFile(s.fileLogPath(), bounded);
            } else if ("PROC".equals(s.kind())) {
                // PROC 源:平台重定向的受管日志文件,FILE 链路复用
                lines = managed.tailFile(supervisor.logFileFor(s.id()), bounded);
            } else {
                lines = docker.logs(s.containerName(), bounded);
            }
            return ApiResponse.ok(lines);
        } catch (Exception e) {
            throw new IllegalStateException("读取日志失败: " + e.getMessage(), e);
        }
    }

    private List<ManagedSourceService.SourceView> managedList() {
        return managed.list();
    }

    /**
     * 日志 SSE 流:先推最近 tail 行,之后每 5s 轮询增量。FILE 源轮询文件尾部,
     * DOCKER 源轮询 docker logs。
     */
    @GetMapping(value = "/sources/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sourceLogsStream(@PathVariable long id,
                                       @RequestParam(value = "tail", defaultValue = "100") int tail) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.id() == id).findFirst().orElse(null);
        if (s == null) {
            throw new IllegalArgumentException("纳管源不存在: " + id);
        }
        SseEmitter emitter = new SseEmitter(300_000L);
        logExecutor.execute(() -> {
            try {
                boolean isFile = "FILE".equals(s.kind());
                boolean isProc = "PROC".equals(s.kind());
                // 初始批次:tail 回放
                List<String> initial = fetchTail(s, Math.max(tail, 100));
                for (String line : initial) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
                // 增量游标:FILE/PROC 用字节偏移,DOCKER 用 --since 时间戳
                // (旧实现按「行数」比较,日志超过 tail 窗口后游标永不前进 → 初始批次后断流)
                String incrementalPath = isFile ? s.fileLogPath()
                        : isProc ? supervisor.logFileFor(s.id()) : null;
                long[] fileOffset = {incrementalPath != null ? new java.io.File(incrementalPath).length() : 0};
                long[] fileMtime = {incrementalPath != null ? new java.io.File(incrementalPath).lastModified() : 0};
                java.time.Instant[] dockerCursor = {java.time.Instant.now()};
                for (int i = 1; i < 60; i++) {
                    Thread.sleep(5000);
                    List<String> fresh;
                    if (incrementalPath != null) {
                        // mtime+长度都没变就跳过读取:避免每 5s 无谓打开文件(多源订阅时空转 IO 放大)
                        java.io.File f = new java.io.File(incrementalPath);
                        long lenNow = f.length();
                        long mtimeNow = f.lastModified();
                        if (lenNow == fileOffset[0] && mtimeNow == fileMtime[0]) {
                            continue;
                        }
                        fileMtime[0] = mtimeNow;
                        ManagedSourceService.TailChunk chunk = managed.readFrom(incrementalPath, fileOffset[0]);
                        fileOffset[0] = chunk.nextOffset();
                        fresh = chunk.lines();
                    } else {
                        fresh = docker.logsSince(s.containerName(), dockerCursor[0].minusMillis(500));
                        dockerCursor[0] = java.time.Instant.now();
                    }
                    for (String line : fresh) {
                        emitter.send(SseEmitter.event().name("log").data(line));
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.complete(); // 客户端断开或读失败:静默收尾
            }
        });
        return emitter;
    }

    private List<String> fetchTail(ManagedSourceService.SourceView s, int bounded) {
        try {
            if ("FILE".equals(s.kind())) {
                return managed.tailFile(s.fileLogPath(), bounded);
            }
            if ("PROC".equals(s.kind())) {
                return managed.tailFile(supervisor.logFileFor(s.id()), bounded);
            }
            return docker.logs(s.containerName(), bounded);
        } catch (Exception e) {
            return List.of("ERROR: 读取日志失败: " + e.getMessage());
        }
    }

    // ---------- AI 日志分析(只读) ----------

    /**
     * AI 分析纳管源最近日志(只读,不改任何状态):取最近 maxLines 行组装
     * prompt 调 agent-service 一次性端点。权限档 ASSIST——只读工具(读日志、
     * 只读 SQL、RAG 检索)自动执行,写操作/容器控制被审批门拦截(机对机无
     * 会话即直接拒绝),保证「只让分析」。
     *
     * <p>body: {tail?: number}。返回 {status, analysis}。
     */
    @PostMapping("/sources/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        ManagedSourceService.SourceView s = managedList().stream()
                .filter(x -> x.id() == id).findFirst().orElse(null);
        if (s == null) {
            throw new IllegalArgumentException("纳管源不存在: " + id);
        }
        int tail = 100;
        if (body != null && body.get("tail") instanceof Number n) {
            tail = Math.min(Math.max(n.intValue(), 20), 300);
        }
        List<String> lines = fetchTail(s, tail);
        if (lines.isEmpty()) {
            return Map.of("status", "completed", "analysis", "最近日志为空,服务可能刚启动或无输出。");
        }
        // 只取最近 100 行且单行截断,控制 prompt 体量
        List<String> recent = lines.subList(Math.max(0, lines.size() - 100), lines.size());
        StringBuilder sb = new StringBuilder();
        for (String line : recent) {
            sb.append(line, 0, Math.min(line.length(), 300)).append('\n');
        }
        String prompt =
                "你是服务诊断助手。以下是纳管服务「" + s.name() + "」最近的日志(可能包含业务 ERROR/异常栈)。\n" +
                "请结合知识库检索(该项目文档可能已入库)定位问题根因,给出:1) 是否有异常及严重程度;2) 根因分析;3) 建议(只分析,不要执行任何修改操作)。\n" +
                "若日志无异常,简要说明服务运行状态即可。\n\n最近日志:\n" + sb;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = agentClient.post()
                    .uri("/api/chat/agent/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("prompt", prompt, "permissionMode", "ASSIST"))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (out == null || !"completed".equals(out.get("status"))) {
                Object err = out == null ? "empty response" : out.getOrDefault("error", out.get("status"));
                return Map.of("status", "error", "analysis", "分析失败: " + err);
            }
            return Map.of("status", "completed", "analysis", String.valueOf(out.get("answer")));
        } catch (Exception e) {
            return Map.of("status", "error", "analysis", "分析失败: " + e.getMessage());
        }
    }

    /** 字节数 → 友好展示(与 docker stats 的 GiB/MiB 风格一致)。 */
    private static String humanBytes(long bytes) {
        double gib = bytes / (1024.0 * 1024 * 1024);
        if (gib >= 1) {
            return String.format("%.2fGiB", gib);
        }
        double mib = bytes / (1024.0 * 1024);
        if (mib >= 1) {
            return String.format("%.1fMiB", mib);
        }
        return String.format("%.0fKiB", bytes / 1024.0);
    }

    /** Action result payload. */
    public record StatusView(String status, String detail) {
    }
}
