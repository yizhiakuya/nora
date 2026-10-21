package com.nora.env.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nora.agent.dto.ChatRequest;
import com.nora.agent.service.ChatRunService;
import com.nora.common.response.ApiResponse;
import com.nora.env.service.DockerClientService;
import com.nora.env.service.EnvironmentService;
import com.nora.env.service.ManagedSourceService;
import com.nora.env.service.ProcessSupervisorService;

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
    private final EnvironmentService environment;
    private final ChatRunService runs;
    private final ExecutorService logExecutor = Executors.newCachedThreadPool();

    public ServiceController(DockerClientService docker, ManagedSourceService managed,
                             ProcessSupervisorService supervisor, EnvironmentService environment, ChatRunService runs) {
        this.docker = docker;
        this.managed = managed;
        this.supervisor = supervisor;
        this.environment = environment;
        this.runs = runs;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() { logExecutor.shutdownNow(); }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/services")
    public ApiResponse<List<Map<String, Object>>> services() { return ApiResponse.ok(environment.services()); }

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

    @PostMapping("/services/{name}/start")
    public ApiResponse<EnvironmentService.StatusView> start(@PathVariable String name) {
        return ApiResponse.ok(environment.control(name, "start"));
    }

    @PostMapping("/services/{name}/stop")
    public ApiResponse<EnvironmentService.StatusView> stop(@PathVariable String name) {
        return ApiResponse.ok(environment.control(name, "stop"));
    }

    @PostMapping("/services/{name}/restart")
    public ApiResponse<EnvironmentService.StatusView> restart(@PathVariable String name) {
        return ApiResponse.ok(environment.control(name, "restart"));
    }

    @GetMapping("/sources/{id}/logs")
    public ApiResponse<List<String>> sourceLogs(@PathVariable long id,
            @RequestParam(value = "tail", defaultValue = "200") int tail) {
        return ApiResponse.ok(environment.logs(id, tail));
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
                List<String> initial = fetchTail(s, Math.clamp(tail, 100, 500));
                for (String line : initial) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
                // 增量游标:FILE/PROC 用字节偏移,DOCKER 用 --since 时间戳
                // (旧实现按「行数」比较,日志超过 tail 窗口后游标永不前进 → 初始批次后断流)
                String incrementalPath = isFile ? environment.logPathOf(s)
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

    private List<String> fetchTail(ManagedSourceService.SourceView source, int limit) {
        return environment.logs(source.id(), limit);
    }

    /** 创建持久化诊断 Run；无人值守 ASSIST 拒绝需要审批的工具。 */
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
        if (sb.length() > 7000) sb.delete(0, sb.length() - 7000);
        String prompt =
                "你是服务诊断助手。以下是纳管服务「" + s.name() + "」最近的日志(可能包含业务 ERROR/异常栈)。\n" +
                "请结合知识库检索(该项目文档可能已入库)定位问题根因,给出:1) 是否有异常及严重程度;2) 根因分析;3) 建议(只分析,不要执行任何修改操作)。\n" +
                "若日志无异常,简要说明服务运行状态即可。\n\n最近日志:\n" + sb;
        String sessionId = "diagnostic-" + java.util.UUID.randomUUID();
        try {
            var result = runs.start(sessionId, new ChatRequest(prompt, null, null, "assist", null,
                    null, "diagnostic", true, "诊断 " + s.name())).completion().get();
            return Map.of("status", result.status(), "analysis", result.error() == null ? result.answer()
                    : result.answer() + "\n分析失败: " + result.error(), "sessionId", sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("status", "unknown", "analysis", "等待已中断，请查看诊断会话", "sessionId", sessionId);
        } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
            return Map.of("status", "error", "analysis", "分析失败: " + e.getMessage(), "sessionId", sessionId);
        }
    }
}
