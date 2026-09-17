package com.nora.env.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * PROC 纳管源的进程守护(supervisor):spawn 平台拉起的本地程序
 * (java -jar / node / 任意可执行),stdout/stderr 合并重定向到受管日志
 * (proc-{id}.log),期望状态调和 + 崩溃退避拉起。
 *
 * <p>设计要点(对齐通用运维面板实践):
 * <ul>
 *   <li>期望状态(desired_state)与实际状态分离,10s 轮询调和——进程被外部杀死后自动拉起</li>
 *   <li>崩溃退避:spawn 失败后 60s 内不再尝试,避免 crash loop 空转</li>
 *   <li>停止:terminate → 10s 宽限 → 强杀;env-service 重启后经 ProcessHandle 按 pid 重挂</li>
 *   <li>日志:子进程输出 append 到受管文件,FILE 源的 tail/SSE/AI 分析链路原样复用</li>
 * </ul>
 */
@Service
public class ProcessSupervisorService {

    private static final Logger log = LoggerFactory.getLogger(ProcessSupervisorService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Path logDir;
    /** 存活托管进程(sourceId → 句柄);env-service 重启后按 pid 字段惰性重挂 */
    private final Map<Long, ProcHandle> live = new ConcurrentHashMap<>();
    /** spawn 连续失败的退避标记(sourceId → 上次失败时间) */
    private final Map<Long, Instant> spawnBackoff = new ConcurrentHashMap<>();
    /** 守护事件环形缓冲(死亡/自愈/启动失败),前端通知中心消费;每源去重(状态不变不重发) */
    private final Deque<Map<String, Object>> events = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int MAX_EVENTS = 50;
    /** PROC 内存采样缓存(sourceId → bytes):tasklist 单次数百 ms,不得在 /services 请求线程同步跑 */
    private final Map<Long, Long> memoryCache = new ConcurrentHashMap<>();
    /** 正在采样的 sourceId(单飞防抖) */
    private final Set<Long> sampling = ConcurrentHashMap.newKeySet();
    /** 内存采样线程(tasklist 慢,后台跑;与 15s 定时采样共用) */
    private final ExecutorService memoryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "proc-memory-sampler");
        t.setDaemon(true);
        return t;
    });

    public ProcessSupervisorService(JdbcTemplate jdbcTemplate,
                                    @Value("${nora.proc.log-dir:logs/proc}") String logDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.logDir = Path.of(logDir);
    }

    /** 一条 PROC 源的运行时快照(供 /services 合并)。 */
    public record ProcStatus(boolean alive, Long pid, String logPath, Integer lastExitCode,
                             String desiredState, String uptime, Long memoryBytes) {
    }

    /** 托管进程句柄:新 spawn 的 Process 与按 pid 找回的 ProcessHandle 统一抽象。 */
    private interface ProcRef {
        boolean isAlive();

        Long pid();

        /** 优雅终止 → 超时强杀;返回最终是否退出。 */
        boolean stop(long graceSeconds) throws InterruptedException;
    }

    private record ProcHandle(ProcRef ref, Path logFile) {
    }

    /** Process 实现:完整生命周期控制。 */
    private record LiveProcess(Process process) implements ProcRef {
        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public Long pid() {
            return process.pid();
        }

        @Override
        public boolean stop(long graceSeconds) throws InterruptedException {
            process.destroy();
            if (process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                return true;
            }
            process.destroyForcibly();
            return process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** ProcessHandle 实现(env-service 重启后按 pid 重挂;退出码不可得)。 */
    private record ForeignProcess(ProcessHandle handle) implements ProcRef {
        @Override
        public boolean isAlive() {
            return handle.isAlive();
        }

        @Override
        public Long pid() {
            return handle.pid();
        }

        @Override
        public boolean stop(long graceSeconds) throws InterruptedException {
            handle.destroy();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
            while (System.nanoTime() < deadline && handle.isAlive()) {
                Thread.sleep(200);
            }
            if (!handle.isAlive()) {
                return true;
            }
            handle.destroyForcibly();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && handle.isAlive()) {
                Thread.sleep(200);
            }
            return !handle.isAlive();
        }
    }

    private record ProcRow(long id, String name, String command, String workDir,
                           Long pid, String desiredState, Integer lastExitCode,
                           java.time.OffsetDateTime startedAt) {
    }

    private ProcRow loadProc(long id) {
        List<ProcRow> rows = jdbcTemplate.query(
                "SELECT id, name, command, work_dir, pid, desired_state, last_exit_code, started_at " +
                        "FROM managed_source WHERE id = ? AND kind = 'PROC' AND deleted_at IS NULL",
                (rs, i) -> new ProcRow(rs.getLong("id"), rs.getString("name"), rs.getString("command"),
                        rs.getString("work_dir"),
                        rs.getObject("pid") == null ? null : rs.getLong("pid"),
                        rs.getString("desired_state"),
                        rs.getObject("last_exit_code") == null ? null : rs.getInt("last_exit_code"),
                        rs.getObject("started_at") == null ? null : rs.getObject("started_at", java.time.OffsetDateTime.class)),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 受管日志绝对路径(FILE 源 tail/SSE 链路按它取数)。 */
    public String logFileFor(long sourceId) {
        return logDir.resolve("proc-" + sourceId + ".log").toString();
    }

    // ---------- 启动 / 停止 / 重启 ----------

    /** 启动 PROC 源:spawn 子进程,输出 append 到受管日志。幂等——已存活直接返回。 */
    public synchronized ProcStatus start(long id) throws IOException {
        ProcRow row = loadProc(id);
        if (row == null) {
            throw new IllegalArgumentException("PROC 纳管源不存在: " + id);
        }
        ProcHandle existing = live.get(id);
        if (existing != null && existing.ref().isAlive()) {
            return snapshot(id, existing.ref());
        }
        Instant backoffUntil = spawnBackoff.get(id);
        if (backoffUntil != null && Instant.now().isBefore(backoffUntil)) {
            throw new IllegalStateException("PROC 源启动连续失败,退避中(60s 后重试): " + row.name());
        }
        Files.createDirectories(logDir);
        Path logFile = Path.of(logFileFor(id));
        // 引号感知拆分:java -jar "C:\Program Files\x.jar" --port=9090 → 3 个参数
        List<String> argv = tokenize(row.command());
        // 合并 stdout/stderr 并 append 到受管日志(redirectErrorStream 后 error 同走 output 管道)
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(row.workDir() == null || row.workDir().isBlank() ? null : new File(row.workDir()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            spawnBackoff.put(id, Instant.now().plusSeconds(60));
            throw new IOException("启动失败: " + e.getMessage(), e);
        }
        spawnBackoff.remove(id);
        live.put(id, new ProcHandle(new LiveProcess(process), logFile));
        jdbcTemplate.update(
                "UPDATE managed_source SET pid = ?, desired_state = 'RUNNING', last_exit_code = NULL, started_at = now() WHERE id = ?",
                process.pid(), id);
        log.info("PROC[{}] {} started, pid={}", id, row.name(), process.pid());
        return snapshot(id, live.get(id).ref());
    }

    /** 停止 PROC 源:优雅终止 → 10s 宽限 → 强杀。 */
    public synchronized ProcStatus stop(long id) throws IOException {
        ProcRow row = loadProc(id);
        if (row == null) {
            throw new IllegalArgumentException("PROC 纳管源不存在: " + id);
        }
        ProcHandle handle = live.remove(id);
        ProcRef ref = handle != null ? handle.ref() : byPid(row.pid());
        boolean exited = true;
        if (ref != null && ref.isAlive()) {
            try {
                exited = ref.stop(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!exited) {
                log.warn("PROC[{}] {} failed to exit within grace period", id, row.name());
            }
        }
        jdbcTemplate.update(
                "UPDATE managed_source SET desired_state = 'STOPPED', pid = NULL WHERE id = ?", id);
        memoryCache.remove(id); // 已停进程不留旧内存值
        return snapshot(id, null);
    }

    /** 重启 = 先停再启。 */
    public synchronized ProcStatus restart(long id) throws IOException {
        stop(id);
        return start(id);
    }

    // ---------- 守护调和(10s) ----------

    /**
     * 守护扫描:期望 RUNNING 但进程已死 → 记录退出码并重新拉起;
     * 期望 STOPPED 但进程还活着(外部拉起/上次停止失败)→ 再停一次。
     */
    @Scheduled(fixedDelay = 10_000)
    public void supervise() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, pid, desired_state, last_exit_code " +
                        "FROM managed_source WHERE kind = 'PROC' AND enabled = TRUE AND deleted_at IS NULL");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String desired = String.valueOf(row.get("desired_state"));
            try {
                ProcHandle handle = live.get(id);
                boolean alive = handle != null && handle.ref().isAlive();
                if ("RUNNING".equals(desired)) {
                    if (handle != null && !alive) {
                        Integer exit = safeExit(handle.ref());
                        jdbcTemplate.update(
                                "UPDATE managed_source SET last_exit_code = ?, pid = NULL WHERE id = ?",
                                exit, id);
                        live.remove(id);
                        memoryCache.remove(id);
                        recordEvent(id, (String) row.get("name"), "died",
                                "进程退出" + (exit != null ? ",退出码 " + exit : "") + ",正在自动拉起");
                    }
                    if (!live.containsKey(id)) {
                        // env-service 崩溃重启后子进程可能仍存活(DB pid 有效):按 pid 重挂,
                        // 不重复 spawn——否则拉起端口冲突的重复进程,crash loop
                        Long pid = row.get("pid") == null ? null : ((Number) row.get("pid")).longValue();
                        if (pid != null) {
                            ProcessHandle ph = ProcessHandle.of(pid).orElse(null);
                            if (ph != null && ph.isAlive()) {
                                live.put(id, new ProcHandle(new ForeignProcess(ph), Path.of(logFileFor(id))));
                                log.info("PROC[{}] {} re-attached to surviving pid={}", id, row.get("name"), pid);
                                continue;
                            }
                        }
                        try {
                            start(id);
                            // start 成功且此前记录过死亡 → 自愈事件由 died 事件隐含,不重发
                        } catch (Exception e) {
                            recordEvent(id, (String) row.get("name"), "start_failed", "自动拉起失败: " + e.getMessage());
                            throw e;
                        }
                    }
                } else if ("STOPPED".equals(desired) && alive) {
                    stop(id);
                }
            } catch (Exception e) {
                log.warn("PROC[{}] supervise failed: {}", id, e.getMessage());
            }
        }
    }

    /** 记录守护事件(环形,前端通知中心消费)。 */
    private void recordEvent(long id, String name, String type, String detail) {
        Map<String, Object> ev = new java.util.LinkedHashMap<>();
        ev.put("sourceId", id);
        ev.put("name", name);
        ev.put("type", type);          // died | start_failed
        ev.put("detail", detail);
        ev.put("time", Instant.now().toString());
        events.addLast(ev);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
        log.warn("PROC[{}] {} event={}: {}", id, name, type, detail);
    }

    /** 取 after 之后的守护事件(前端增量拉取;after 为 ISO 时间戳)。 */
    public List<Map<String, Object>> eventsAfter(Instant after) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            Instant t = Instant.parse(String.valueOf(ev.get("time")));
            if (after == null || t.isAfter(after)) {
                out.add(ev);
            }
        }
        return out;
    }

    /** env-service 关闭时停掉所有托管子进程(面板退出不留孤儿进程)。 */
    @PreDestroy
    public void shutdownAll() {
        for (Map.Entry<Long, ProcHandle> e : live.entrySet()) {
            try {
                if (e.getValue().ref().isAlive()) {
                    e.getValue().ref().stop(5);
                }
            } catch (Exception ex) {
                log.warn("PROC[{}] shutdown failed: {}", e.getKey(), ex.getMessage());
            }
        }
        live.clear();
    }

    // ---------- /services 合并用的运行时状态 ----------

    /** PROC 源运行时状态;env-service 重启过则按 pid 探测存活(同机进程可见)。 */
    public ProcStatus status(long id) {
        ProcRow row = loadProc(id);
        if (row == null) {
            return new ProcStatus(false, null, logFileFor(id), null, "STOPPED", null, null);
        }
        ProcHandle handle = live.get(id);
        boolean alive = handle != null && handle.ref().isAlive();
        if (!alive && row.pid() != null) {
            ProcessHandle ph = ProcessHandle.of(row.pid()).orElse(null);
            alive = ph != null && ph.isAlive();
        }
        String uptime = null;
        Long memBytes = null;
        if (alive) {
            uptime = humanUptime(row.startedAt());
            // 只读缓存:tasklist 慢,采样由 scheduleMemorySampling 后台进行
            memBytes = memoryCache.get(id);
            scheduleMemorySampling(id, row.pid());
        }
        return new ProcStatus(alive, alive ? row.pid() : null, logFileFor(id),
                row.lastExitCode(), row.desiredState(), uptime, memBytes);
    }

    // ---------- 内部实现 ----------

    /** 引号感知的命令拆分:双引号内的空格不分段,引号本身不进参数。 */
    private static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (char c : command.trim().toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private ProcRef byPid(Long pid) {
        if (pid == null) {
            return null;
        }
        return ProcessHandle.of(pid).map(ForeignProcess::new).orElse(null);
    }

    private ProcStatus snapshot(long id, ProcRef ref) {
        ProcRow row = loadProc(id);
        boolean alive = ref != null && ref.isAlive();
        if (alive && row != null) {
            // 只读缓存并触发后台采样:start/stop/restart 的响应线程不碰 tasklist
            scheduleMemorySampling(id, row.pid());
        }
        return new ProcStatus(alive, alive ? ref.pid() : null, logFileFor(id),
                row == null ? null : row.lastExitCode(),
                row == null ? "STOPPED" : row.desiredState(),
                alive && row != null ? humanUptime(row.startedAt()) : null,
                alive && row != null ? memoryCache.get(id) : null);
    }

    /** 启动时间 → 友好时长;started_at 未知返回 null。 */
    private static String humanUptime(java.time.OffsetDateTime startedAt) {
        if (startedAt == null) {
            return null;
        }
        java.time.Duration d = java.time.Duration.between(startedAt.toInstant(), Instant.now());
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long min = d.toMinutes() % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + min + "m";
        return min + "m";
    }

    /**
     * PROC 内存采样定时任务(15s):所有存活托管进程批量后台采样进缓存,
     * /services 请求线程只读缓存,不再被 tasklist 拖慢数百 ms/源。
     */
    @Scheduled(initialDelay = 3_000, fixedDelay = 15_000)
    public void sampleMemory() {
        for (Map.Entry<Long, ProcHandle> e : live.entrySet()) {
            Long pid = e.getValue().ref().pid();
            if (pid != null) {
                scheduleMemorySampling(e.getKey(), pid);
            }
        }
    }

    /** 单飞触发单源采样:已在采样的源直接跳过。pid 允许来自外部重挂的 ProcessHandle。 */
    private void scheduleMemorySampling(long id, Long pid) {
        if (pid == null || !sampling.add(id)) {
            return;
        }
        memoryExecutor.execute(() -> {
            try {
                Long bytes = memoryOfPid(pid);
                if (bytes != null) {
                    memoryCache.put(id, bytes);
                } else {
                    memoryCache.remove(id); // 进程退出/采样失败:清掉旧值避免展示过期数据
                }
            } finally {
                sampling.remove(id);
            }
        });
    }

    /** env-service 关闭时停掉采样线程。 */
    @PreDestroy
    void shutdownMemoryExecutor() {
        memoryExecutor.shutdownNow();
    }

    /** 按pid取进程工作集内存(字节);Windows 用 tasklist,失败返回 null(不阻塞状态展示)。 */
    private static Long memoryOfPid(Long pid) {
        if (pid == null) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(5, TimeUnit.SECONDS);
            // 行形如: "foo.exe","1234","Console","1","123,456 K" → 取 K 前数字
            for (String line : out.split("\n")) {
                if (!line.contains("\"" + pid + "\"")) {
                    continue;
                }
                String[] cols = line.split("\"");
                if (cols.length >= 8) {
                    String kb = cols[cols.length - 2].replaceAll("[^0-9]", "");
                    if (!kb.isEmpty()) {
                        return Long.parseLong(kb) * 1024;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("memoryOfPid({}) failed: {}", pid, e.getMessage());
        }
        return null;
    }

    private static Integer safeExit(ProcRef ref) {
        if (ref instanceof LiveProcess lp) {
            try {
                return lp.process().exitValue();
            } catch (IllegalThreadStateException e) {
                return null;
            }
        }
        return null; // 外部进程退出码不可得
    }
}
