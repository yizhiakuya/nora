package com.nora.env.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;

/** 环境查询与控制供 HTTP 和 Agent 共用；不依赖聊天运行入口。 */
@Service
public class EnvironmentService {
    private final DockerClientService docker;
    private final ManagedSourceService managed;
    private final ProcessSupervisorService supervisor;

    public EnvironmentService(DockerClientService docker, ManagedSourceService managed, ProcessSupervisorService supervisor) {
        this.docker = docker;
        this.managed = managed;
        this.supervisor = supervisor;
    }

    public List<Map<String, Object>> services() {
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
                // 文件存在和 mtime 只描述日志，不能证明服务存活或停止。
                String logPath = logPathOf(s);
                Long ageMin = fileAgeMinutes(logPath);
                boolean exists = ageMin != null;
                out.add(new java.util.LinkedHashMap<>(Map.of(
                        "id", String.valueOf(s.id()),
                        "name", s.name(),
                        "kind", "FILE",
                        "fileLogPath", logPath,
                        "status", exists ? "unknown" : "error",
                        "health", "unknown",
                        "detail", (!exists ? "日志文件不存在" : "日志最近更新于 " + humanAge(ageMin))
                                + "；未探测服务运行状态")));
            }
        }
        Map<String, String> observer = Map.of(
                "host", System.getenv().getOrDefault(System.getProperty("os.name").startsWith("Windows") ? "COMPUTERNAME" : "HOSTNAME", "未报告"),
                "platform", System.getProperty("os.name"),
                "scope", java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"))
                        || java.nio.file.Files.exists(java.nio.file.Path.of("/run/.containerenv")) ? "container" : "host");
        out.forEach(item -> item.put("observer", observer));
        return out;
    }

    /**
     * FILE 源日志路径解析(2026-10-01 跨环境部署修复):
     * 裸文件名(如 {@code nora-text.log})= 当前环境的日志目录
     * ({@code LOG_PATH} 环境变量,缺省 {@code logs};容器由镜像设 {@code /app/logs})下的文件;
     * 含路径分隔符的值视为绝对/自定义路径,原样使用。
     *
     * <p>V11 迁移把种子源归一化为裸文件名——开发机与容器(卷 /app/logs)下
     * 都能解析到实际日志,不再是写死的 Windows 绝对路径。
     */
    public String logPathOf(ManagedSourceService.SourceView s) {
        String path = s.fileLogPath();
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.contains("/") || path.contains("\\")) {
            return path;
        }
        String logDir = System.getenv("LOG_PATH");
        if (logDir == null || logDir.isBlank()) {
            logDir = "logs";
        }
        return logDir.replaceAll("/+$", "") + "/" + path;
    }

    /** 日志文件距今的分钟数;文件不存在/不可读返回 null。 */
    private Long fileAgeMinutes(String path) {
        try {
            java.io.File f = new java.io.File(path);
            // 必须显式 isFile():File.lastModified() 对不存在的文件返回 0 而不抛异常,
            // 直接相减会算出 1970 年以来的分钟数(实测「日志活跃于 497091 小时前」),
            // 且让上层的「日志文件不存在」分支永远不触发。
            if (!f.isFile()) {
                return null;
            }
            long ageMs = System.currentTimeMillis() - f.lastModified();
            return Math.max(0, ageMs / 60_000);
        } catch (Exception e) {
            return null;
        }
    }

    /** 分钟数 → 友好描述。 */
    private String humanAge(long min) {
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 48) return h + " 小时前";
        return (h / 24) + " 天前";
    }


    public ManagedSourceService.SourceView source(long id) {
        return managed.list().stream().filter(row -> row.id() == id).findFirst()
                .orElseThrow(() -> BusinessException.notFound("纳管源不存在: " + id));
    }

    public List<String> logs(long id, int tail) {
        var source = source(id);
        int bounded = Math.clamp(tail, 1, 500);
        try {
            return switch (source.kind()) {
                case "FILE" -> managed.tailFile(logPathOf(source), bounded);
                case "PROC" -> managed.tailFile(supervisor.logFileFor(id), bounded);
                default -> docker.logs(source.containerName(), bounded);
            };
        } catch (java.io.IOException e) {
            throw BusinessException.dependency("LOG_READ_FAILED", "读取日志失败: " + e.getMessage(), "请检查日志路径与访问权限");
        }
    }

    public StatusView control(String name, String action) {
        if (name == null || name.isBlank()) throw BusinessException.validation("service name is required");
        if (!List.of("start", "stop", "restart").contains(action)) throw BusinessException.validation("invalid action");
        var source = managed.list().stream().filter(row -> row.enabled() && name.equals(row.name())).findFirst().orElse(null);
        if (source != null && "PROC".equals(source.kind())) {
            try {
            var status = switch (action) {
                case "start" -> supervisor.start(source.id());
                case "stop" -> supervisor.stop(source.id());
                default -> supervisor.restart(source.id());
            };
            boolean success = "stop".equals(action) ? !status.alive() : status.alive();
            return new StatusView(success ? (status.alive() ? "running" : "stopped") : "error",
                    success ? "已执行 " + action : "操作后进程状态不符合预期");
            } catch (java.io.IOException e) { return new StatusView("error", "ERROR: " + e.getMessage()); }
        }
        if (source != null && !"DOCKER".equals(source.kind())) {
            throw BusinessException.validation("日志源不支持启停操作");
        }
        String container = source == null ? name : source.containerName();
        String detail = switch (action) {
            case "start" -> docker.start(container);
            case "stop" -> docker.stop(container);
            default -> docker.restart(container);
        };
        return new StatusView(detail.startsWith("ERROR") ? "error" : "stop".equals(action) ? "stopped" : "running", detail);
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

    public record StatusView(String status, String detail) {}
}
