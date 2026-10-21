package com.nora.env.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * Docker 门面。v1 外调 {@code docker} CLI(简单、无额外依赖、兼容 Docker
 * Desktop 的命名管道);JSON 输出模式保持解析稳定。后续阶段可换 Docker HTTP API。
 */
@Service
public class DockerClientService {

    private static final Logger log = LoggerFactory.getLogger(DockerClientService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * docker stats 缓存:stats --no-stream 单次要 1.5~3s,直接在 /services
     * 请求里跑会拖慢环境控制台首屏,改为后台刷新 + 读缓存(见 {@link #stats()})。
     */
    private volatile Map<String, String[]> statsCache = Map.of();
    /** 最近一次成功采样的时间戳(ms) */
    private volatile long statsAt = 0;
    private static final long STATS_TTL_MS = 20_000;
    /** stats 后台刷新线程(单线程;单飞标记防止并发请求叠出多个 docker stats) */
    private final ExecutorService statsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docker-stats-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean statsRefreshing = new AtomicBoolean(false);

    /** 前端 ServiceInstance 消费的一个纳管容器。 */
    public record ContainerView(
            String id,
            String name,
            String image,
            String status,       // running / stopped / error
            String health,       // healthy / degraded / down
            String port,
            String uptime,
            String cpu,
            String memory) {
    }

    /** 经 docker ps 以 JSON 列出容器(running + exited)。 */
    public List<ContainerView> list() {
        try {
            String raw = exec("docker", "ps", "-a",
                    "--filter", "label=nora.managed=true",
                    "--format", "{{json .}}");
            List<ContainerView> result = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                result.add(toView(node));
            }
            if (result.isEmpty()) {
                // 还没有带标签的容器:回退到全部运行中容器
                return listAll();
            }
            return result;
        } catch (Exception e) {
            log.warn("docker ps failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 一次性取所有容器的即时资源占用(name → [cpu, memUsage])。
     *
     * <p>阻塞版实现在后台线程跑,本方法只读缓存立即返回:缓存过期(>20s)时
     * 触发一次单飞异步刷新,另由 {@link #warmStatsCache()} 定时预热,保证
     * 服务启动后缓存温热。资源数据最多滞后一个刷新周期,监控展示可接受。
     */
    public Map<String, String[]> stats() {
        if (System.currentTimeMillis() - statsAt > STATS_TTL_MS) {
            refreshStatsAsync();
        }
        return statsCache;
    }

    /** 单飞后台刷新:已在刷新中直接跳过,避免并发请求叠出多个 docker stats。 */
    private void refreshStatsAsync() {
        if (!statsRefreshing.compareAndSet(false, true)) {
            return;
        }
        statsExecutor.execute(() -> {
            try {
                Map<String, String[]> fresh = fetchStats();
                if (fresh != null) {
                    statsCache = fresh;
                    statsAt = System.currentTimeMillis();
                } else {
                    // 采样失败:只推时间戳,保留旧缓存(TTL 过期下轮再试)
                    statsAt = System.currentTimeMillis();
                }
            } finally {
                statsRefreshing.set(false);
            }
        });
    }

    /** 环境页 30s 轮询之外兜底预热:env-service 启动后缓存始终温热,首屏也有数据。 */
    @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
    public void warmStatsCache() {
        refreshStatsAsync();
    }

    @PreDestroy
    void shutdownStatsExecutor() {
        statsExecutor.shutdownNow();
    }

    /**
     * 实际执行 docker stats(阻塞 1.5~3s),只在后台线程调用。
     * 失败时返回 null 表示「保留上一轮缓存」,不覆盖好数据。
     */
    private Map<String, String[]> fetchStats() {
        try {
            String raw = exec("docker", "stats", "--no-stream", "--format", "{{json .}}");
            Map<String, String[]> out = new java.util.HashMap<>();
            for (String line : raw.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String name = node.path("Name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                out.put(name, new String[]{
                        node.path("CPUPerc").asText("—"),
                        node.path("MemUsage").asText("—")});
            }
            return out;
        } catch (Exception e) {
            log.warn("docker stats failed (keeping previous cache): {}", e.getMessage());
            return null;
        }
    }

    private List<ContainerView> listAll() throws Exception {
        String raw = exec("docker", "ps", "--format", "{{json .}}");
        List<ContainerView> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            result.add(toView(objectMapper.readTree(line)));
        }
        return result;
    }

    private ContainerView toView(JsonNode node) {
        String state = node.path("State").asText("unknown");
        String status = switch (state) {
            case "running" -> "running";
            case "exited", "dead", "created" -> "stopped";
            default -> "error";
        };
        String health = "running".equals(status) ? "healthy" : "down";
        if (status.equals("running") && node.path("Status").asText("").contains("unhealthy")) {
            health = "degraded";
        }
        return new ContainerView(
                node.path("ID").asText(),
                node.path("Names").asText(),
                node.path("Image").asText(),
                status,
                health,
                node.path("Ports").asText("").isEmpty()
                        ? "—" : firstPort(node.path("Ports").asText()),
                node.path("Status").asText("—"),
                "—",  // stats require a second call; filled lazily by the UI later
                "—");
    }

    private static String firstPort(String ports) {
        // 如 "0.0.0.0:5432->5432/tcp, ..." → "5432"
        int arrow = ports.indexOf("->");
        if (arrow > 0) {
            String before = ports.substring(0, arrow);
            int colon = before.lastIndexOf(':');
            return colon >= 0 ? before.substring(colon + 1) : before;
        }
        return ports.split("/")[0];
    }

    /** 按名称或 id 启动容器。 */
    public String start(String idOrName) {
        return execIgnoringError("docker", "start", idOrName);
    }

    /** 按名称或 id 停止容器。 */
    public String stop(String idOrName) {
        return execIgnoringError("docker", "stop", idOrName);
    }

    /** 按名称或 id 重启容器。 */
    public String restart(String idOrName) {
        return execIgnoringError("docker", "restart", idOrName);
    }

    /**
     * 取容器最近日志(不带 follow——SSE 端点用重复轮询流式推送,比裸
     * {@code --follow} 管道更能扛网关跳转)。
     *
     * @param idOrName 容器名或 id
     * @param tail     最近行数
     * @return 日志行,最新在最后
     */
    public List<String> logs(String idOrName, int tail) {
        try {
            String raw = exec("docker", "logs", "--tail", String.valueOf(tail), idOrName);
            if (raw.isBlank()) {
                // 很多官方镜像(postgres 等)把日志全部写 stderr,stdout 为空时合并读取
                raw = execMergeStderr("docker", "logs", "--tail", String.valueOf(tail), idOrName);
            }
            if (raw.isBlank()) {
                return List.of();
            }
            return List.of(raw.stripTrailing().split("\n"));
        } catch (Exception e) {
            return List.of("ERROR: " + e.getMessage());
        }
    }

    /** 增量日志:返回 since 之后写入的行(docker logs --since,RFC3339 时间戳)。 */
    public List<String> logsSince(String idOrName, Instant since) {
        String sinceSpec = since.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
        try {
            String raw = exec("docker", "logs", "--since", sinceSpec, idOrName);
            if (raw.isBlank()) {
                raw = execMergeStderr("docker", "logs", "--since", sinceSpec, idOrName);
            }
            if (raw.isBlank()) {
                return List.of();
            }
            return List.of(raw.stripTrailing().split("\n"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 同 {@link #exec},但把 stderr 并入 stdout(多数镜像的 docker logs 写在那里)。 */
    private String execMergeStderr(String... command) throws Exception {        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(System.getenv());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        java.util.concurrent.CompletableFuture<String> merged =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> readStream(process.getInputStream()));
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("docker command timed out");
        }
        return merged.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private String exec(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(System.getenv());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        // stdout/stderr 必须并发排空:同步 readAllBytes 会因管道不关闭而永久阻塞
        // (Windows docker CLI 常见),让 30s 超时保护完全失效
        java.util.concurrent.CompletableFuture<String> stdout =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> readStream(process.getInputStream()));
        java.util.concurrent.CompletableFuture<String> stderr =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> readStream(process.getErrorStream()));
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("docker command timed out");
        }
        String output = stdout.get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            String err = stderr.get(5, java.util.concurrent.TimeUnit.SECONDS);
            throw new IllegalStateException(err.isBlank() ? "exit " + process.exitValue() : err.strip());
        }
        return output;
    }

    private static String readStream(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String execIgnoringError(String... command) {
        try {
            String out = exec(command);
            return out.isBlank() ? "ok" : out.strip();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String humanUptime(Instant startedAt) {
        Duration d = Duration.between(startedAt, Instant.now());
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
