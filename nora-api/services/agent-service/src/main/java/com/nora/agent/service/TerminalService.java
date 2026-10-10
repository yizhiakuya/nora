package com.nora.agent.service;

import java.io.InputStreamReader;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.nora.agent.dto.CommandExecutionDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 平台原生命令执行：一个进程、独立 watchdog、持续日志，以及可查询的后台生命周期。 */
@Service
public class TerminalService {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    public static final int DEFAULT_TIMEOUT_SEC = 1800;
    static final int MAX_COMMAND_CHARS = 8_000;
    static final int PROGRESS_PREVIEW_CHARS = 4_000;
    private static final long MAX_LOG_BYTES = 64L * 1024 * 1024;
    private final Semaphore slots = new Semaphore(4);
    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<CommandExecutionDto>> completions = new ConcurrentHashMap<>();
    private CommandExecutionStore executionStore;
    private TurnStreamRegistry turnStreams;

    @Autowired
    void configureExecutions(CommandExecutionStore store, TurnStreamRegistry streams) {
        this.executionStore = store;
        this.turnStreams = streams;
    }

    @PostConstruct
    void recoverExecutions() { if (executionStore != null) executionStore.recover(); }

    @PreDestroy
    public void shutdown() {
        executions.values().forEach(execution -> execution.stop("cancelled", "服务正在关闭，命令已停止。"));
        for (Execution execution : executions.values()) {
            try { execution.done.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { log.warn("command shutdown: {}", e.getMessage()); }
        }
    }

    /** 内置 PowerShell 版本(随仓库 tools/pwsh 经 git-lfs 分发,首次使用解压到缓存目录)。 */
    static final String BUNDLED_PWSH_VERSION = "7.6.6";
    /** 内置 PowerShell 归档的默认路径(仓库根 tools/pwsh;可配 nora.agent.pwsh-bundle;仅 Windows 使用)。 */
    static final String DEFAULT_PWSH_BUNDLE = "tools/pwsh/PowerShell-" + BUNDLED_PWSH_VERSION + "-win-x64.zip";

    private final Path workspaceRoot;
    /** 显式指定的 pwsh 可执行文件(可配 nora.agent.powershell);空=自动(内置→PATH)。 */
    private final String pwshOverride;
    /** 内置 pwsh 归档路径(可配 nora.agent.pwsh-bundle);null/空=禁用内置。 */
    private final Path pwshBundle;
    /**
     * 应用设置存储(可空:测试不接)——用于读取用户自定义环境变量,
     * 执行命令时注入子进程(2026-09-19 设置页「环境变量」真实落地)。
     */
    private final AppSettingStore appSettingStore;
    private final EnvVarsService envVarsService;

    @Autowired
    public TerminalService(
            @Value("${nora.agent.workspace:" + AgentWorkspaceService.DEFAULT_ROOT + "}") String workspacePath,
            @Value("${nora.agent.powershell:}") String pwshOverride,
            @Value("${nora.agent.pwsh-bundle:" + DEFAULT_PWSH_BUNDLE + "}") String pwshBundle,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AppSettingStore appSettingStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) EnvVarsService envVarsService) {
        this.workspaceRoot = Path.of(workspacePath).toAbsolutePath().normalize();
        this.pwshOverride = pwshOverride;
        this.pwshBundle = (pwshBundle == null || pwshBundle.isBlank()) ? null : Path.of(pwshBundle).toAbsolutePath().normalize();
        this.appSettingStore = appSettingStore;
        this.envVarsService = envVarsService;
    }

    /** 便捷构造:无 appSettingStore(测试/旧调用方)。 */
    public TerminalService(String workspacePath, String pwshOverride, String pwshBundle) {
        this(workspacePath, pwshOverride, pwshBundle, null, null);
    }

    /** 便捷构造器(测试用):工作区 + 默认内置 pwsh,无显式覆盖。 */
    public TerminalService(String workspacePath) {
        this(workspacePath, "", DEFAULT_PWSH_BUNDLE, null, null);
    }

    /** 同步便捷入口也使用同一个进程生命周期，仅用于直接调用/冒烟。 */
    public record RunResult(String output, int exitCode, boolean timedOut, boolean cancelled,
                            long durationMs, String cwdUsed, int timeoutSec) {
        public String render() {
            return (output == null || output.isEmpty() ? "(无输出)" : output) + "\n---\n"
                    + (cancelled ? "(命令已取消,进程已终止)" : timedOut ? "(命令超时 " + timeoutSec + "s,进程树已终止)"
                    : "(exit code: " + exitCode + ", " + durationMs + "ms, cwd: " + cwdUsed + ")");
        }
    }

    public RunResult run(String command, String cwd, Integer timeout) { return run(command, cwd, timeout, null); }

    public RunResult run(String command, String cwd, Integer timeout, Consumer<String> progress) {
        var execution = start("smoke-" + java.util.UUID.randomUUID(), null, command, cwd, timeout, command);
        try {
            while (execution.running()) execution = await(execution.sessionId(), execution.executionId(), 1000, progress, () -> false);
        } catch (RuntimeException e) {
            stop(execution.sessionId(), execution.executionId());
            throw e;
        }
        var result = get(execution.sessionId(), execution.executionId());
        return new RunResult(result.output(), result.exitCode() == null ? -1 : result.exitCode(),
                "timed_out".equals(result.status()), "cancelled".equals(result.status()),
                result.elapsedMs(), result.cwd(), result.timeoutSeconds());
    }

    public int defaultTimeout() {
        var settings = appSettingStore == null ? null : appSettingStore.raw("agent");
        Object value = settings == null ? null : settings.get("commandTimeoutSeconds");
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : DEFAULT_TIMEOUT_SEC;
    }

    public CommandExecutionDto start(String sessionId, String stepId, String command, String cwdArg,
                                     Integer timeoutSec, String title) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("缺少 sessionId");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("缺少 command 参数");
        if (command.length() > MAX_COMMAND_CHARS) throw new IllegalArgumentException("命令超过 " + MAX_COMMAND_CHARS + " 字符上限");
        int timeout = timeoutSec == null ? defaultTimeout() : timeoutSec;
        if (timeout < 1) throw new IllegalArgumentException("timeout 必须是正整数秒数");
        Path cwd = resolveCwd(cwdArg);
        if (!Files.isDirectory(cwd)) throw new IllegalArgumentException("工作目录不存在: " + cwd);
        if (!slots.tryAcquire()) throw new IllegalStateException("已有 4 个命令在运行；请等待或停止已有任务后再启动。");
        Execution execution = null;
        try {
            String id = java.util.UUID.randomUUID().toString();
            Path logPath = workspaceRoot.resolve("command-logs").resolve(id + ".log");
            Files.createDirectories(logPath.getParent());
            var turn = turnStreams == null ? null : turnStreams.get(sessionId);
            execution = new Execution(id, sessionId, turn == null ? null : turn.turnId, stepId,
                    title, command, cwd, timeout, logPath);
            if (executionStore != null) executionStore.save(execution.snapshot());
            List<String> argv = new ArrayList<>(buildArgv(command));
            // Linux 会话组拥有整棵子进程树，即便父 shell 先退出也可清理。
            if (System.getProperty("os.name", "").startsWith("Linux")) {
                Path setsid = Path.of("/usr/bin/setsid");
                if (!Files.isExecutable(setsid)) setsid = Path.of("/bin/setsid");
                if (!Files.isExecutable(setsid)) throw new IllegalStateException("缺少 setsid，请安装 util-linux");
                argv.addFirst(setsid.toString());
                execution.processGroup = true;
            }
            ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
            if (envVarsService != null) envVarsService.resolveForExecution()
                    .forEach(builder.environment()::put);
            execution.process = builder.start();
            if (isWindows()) execution.windowsJob = new WindowsCommandJob(execution.process.pid());
            execution.process.getOutputStream().close();
            executions.put(id, execution);
            Execution owned = execution;
            Thread.ofVirtual().name("command-" + id).start(owned::run);
            return execution.snapshot();
        } catch (Exception e) {
            slots.release();
            if (execution != null) {
                execution.stop("failed", "命令启动失败: " + e.getMessage());
                execution.finishedAt = System.currentTimeMillis();
                if (executionStore != null) executionStore.save(execution.snapshot());
            }
            throw new IllegalStateException("命令启动失败: " + e.getMessage(), e);
        }
    }

    public CommandExecutionDto await(String sessionId, String id, int yieldMs, Consumer<String> progress,
                                     BooleanSupplier cancelled) {
        if (yieldMs < 0 || yieldMs > 30_000) throw new IllegalArgumentException("yieldMs 必须在 0–30000 之间");
        Execution execution = executions.get(id);
        if (execution == null) return get(sessionId, id);
        if (!execution.sessionId.equals(sessionId)) throw new IllegalArgumentException("命令任务不存在: " + id);
        long end = System.nanoTime() + yieldMs * 1_000_000L;
        long sequence = -1;
        try {
            do {
                if (cancelled != null && cancelled.getAsBoolean()) execution.stop("cancelled", "用户已停止当前轮次。");
                var snapshot = execution.snapshot();
                if (progress != null && snapshot.outputChars() != sequence && !snapshot.output().isEmpty()) {
                    sequence = snapshot.outputChars();
                    progress.accept(snapshot.output());
                }
                if (execution.done.isDone() || yieldMs == 0) return snapshot;
                long remaining = end - System.nanoTime();
                if (remaining <= 0) return snapshot;
                try { return execution.done.get(Math.min(250, Math.max(1, remaining / 1_000_000)), TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { /* 下一次快照，进程 watchdog 独立于调用者。 */ }
            } while (true);
        } catch (InterruptedException e) {
            execution.stop("cancelled", "用户已停止当前轮次。");
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("command wait interrupted");
        } catch (java.util.concurrent.ExecutionException e) { throw new IllegalStateException(e.getCause()); }
    }

    public CommandExecutionDto get(String sessionId, String id) {
        var execution = executions.get(id);
        if (execution != null && execution.sessionId.equals(sessionId)) return execution.snapshot();
        if (executionStore != null) return executionStore.find(sessionId, id);
        throw new IllegalArgumentException("命令任务不存在: " + id);
    }

    public java.util.Map<String, CommandExecutionDto> snapshots(String sessionId, java.util.Set<String> ids) {
        var result = new java.util.HashMap<String, CommandExecutionDto>();
        if (executionStore != null) executionStore.findAll(sessionId, ids).forEach(item -> result.put(item.executionId(), item));
        executions.values().stream().filter(item -> item.sessionId.equals(sessionId) && ids.contains(item.id))
                .forEach(item -> result.put(item.id, item.snapshot()));
        return result;
    }

    public List<CommandExecutionDto> list(String sessionId) {
        var result = new java.util.LinkedHashMap<String, CommandExecutionDto>();
        if (executionStore != null) executionStore.list(sessionId).forEach(item -> result.put(item.executionId(), item));
        executions.values().stream().filter(item -> item.sessionId.equals(sessionId))
                .forEach(item -> result.put(item.id, item.snapshot()));
        return result.values().stream().sorted(java.util.Comparator.comparingLong(CommandExecutionDto::startedAt).reversed())
                .map(CommandExecutionDto::withoutOutput).toList();
    }

    public CommandExecutionDto stop(String sessionId, String id) {
        get(sessionId, id); // 验证会话归属，禁止操作另一会话的进程。
        var execution = executions.get(id);
        if (execution != null) execution.stop("cancelled", "用户已停止命令。");
        return get(sessionId, id);
    }

    public void stopTurn(String sessionId, String turnId) {
        executions.values().stream().filter(item -> item.sessionId.equals(sessionId) && java.util.Objects.equals(item.turnId, turnId))
                .forEach(item -> item.stop("cancelled", "用户已停止当前轮次。"));
    }

    public void clearFinished(String sessionId) {
        if (executionStore != null) executionStore.clearFinished(sessionId);
        executions.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId) && entry.getValue().done.isDone());
    }

    public List<CommandExecutionDto> takeCompletions(String sessionId) {
        var queue = sessionId == null ? null : completions.get(sessionId);
        List<CommandExecutionDto> result = new ArrayList<>();
        if (queue != null) { CommandExecutionDto item; while ((item = queue.poll()) != null) result.add(item); }
        return result;
    }

    private final class Execution {
        final List<String> secrets = envVarsService == null ? List.of() : envVarsService.secrets();
        final String id, sessionId, turnId, stepId, title, command, cwd;
        final Path logPath;
        final int timeout;
        final long startedAt = System.currentTimeMillis(), startedNanos = System.nanoTime();
        final CompletableFuture<CommandExecutionDto> done = new CompletableFuture<>();
        final java.util.Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        final StringBuilder output = new StringBuilder();
        volatile Process process;
        WindowsCommandJob windowsJob;
        volatile boolean processGroup;
        volatile String status = "running", error;
        volatile Long finishedAt;
        volatile Integer exitCode;
        volatile long lastOutputAt = startedAt, outputChars;

        Execution(String id, String sessionId, String turnId, String stepId, String title, String command, Path cwd, int timeout, Path logPath) {
            this.id = id; this.sessionId = sessionId; this.turnId = turnId; this.stepId = stepId;
            this.title = Texts.abbreviate(EnvVarsService.redact(title == null ? command : title, secrets), 160);
            this.command = EnvVarsService.redact(command, secrets); this.cwd = cwd.toString(); this.timeout = timeout; this.logPath = logPath;
        }

        CommandExecutionDto snapshot() {
            String preview;
            synchronized (output) { preview = livePreview == null ? output.toString() : livePreview; }
            return new CommandExecutionDto(id, sessionId, turnId, stepId, title, command, cwd, toolName(), status,
                    startedAt, finishedAt, (finishedAt == null ? System.currentTimeMillis() : finishedAt) - startedAt,
                    timeout, lastOutputAt, outputChars, preview, "workspace:command-logs/" + id + ".log", exitCode, error);
        }

        void run() {
            Thread reader = Thread.ofVirtual().name("command-output-" + id).start(this::readOutput);
            try {
                while (process.isAlive()) {
                    children.addAll(process.descendants().toList());
                    if ((System.nanoTime() - startedNanos) / 1_000_000 >= timeout * 1000L) stop("timed_out", "命令超时 " + timeout + " 秒，进程树已终止。");
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                }
                exitCode = process.exitValue();
            } catch (InterruptedException e) { stop("cancelled", "命令执行被中断。"); Thread.currentThread().interrupt(); }
            finally {
                killTree();
                try { reader.join(2000); if (reader.isAlive()) { process.getInputStream().close(); reader.join(1000); } }
                catch (IOException | InterruptedException e) { log.warn("command output cleanup: {}", e.getMessage()); }
                synchronized (this) { if ("running".equals(status)) status = exitCode == 0 ? "completed" : "failed"; }
                finishedAt = System.currentTimeMillis();
                var snapshot = snapshot();
                try {
                    if (executionStore != null) executionStore.save(snapshot);
                    var queue = completions.computeIfAbsent(sessionId, key -> new java.util.concurrent.ConcurrentLinkedQueue<>());
                    queue.add(snapshot);
                    // ponytail: 每会话保留最近 100 条待消费通知；完整记录仍在持久化历史。
                    while (queue.size() > 100) queue.poll();
                    if (executionStore != null) executions.remove(id, this);
                } catch (RuntimeException e) { log.error("Cannot save command terminal state {}", id, e); }
                finally { done.complete(snapshot); slots.release(); }
            }
        }

        synchronized void stop(String reason, String message) {
            if (!"running".equals(status)) return;
            status = reason; error = message;
            killTree();
        }

        void killTree() {
            if (process == null) return;
            if (windowsJob != null) windowsJob.close();
            if (processGroup) {
                try {
                    Process killer = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + process.pid()).start();
                    if (!killer.waitFor(2, TimeUnit.SECONDS)) killer.destroyForcibly();
                } catch (IOException | InterruptedException e) { log.warn("command process group cleanup: {}", e.getMessage()); }
            }
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) killProcessTree(process);
        }

        void readOutput() {
            StreamRedactor redactor = new StreamRedactor(secrets);
            long bytes = 0;
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
                 Writer writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    String text = new String(chunk, 0, count);
                    bytes += text.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_LOG_BYTES) { stop("failed", "命令输出超过 64 MiB 日志上限，进程已停止。"); break; }
                    String safe = redactor.accept(text, false);
                    writer.write(safe); writer.flush();
                    synchronized (output) {
                        String full = output.toString() + safe;
                        String preview = stripAnsi(stripClixml(full + redactor.preview()));
                        output.setLength(0); output.append(tail(full, PROGRESS_PREVIEW_CHARS));
                        // 未结束的脱敏缓冲仅用于预览，不重复写入日志。
                        livePreview = tail(preview, PROGRESS_PREVIEW_CHARS);
                    }
                    outputChars += count; lastOutputAt = System.currentTimeMillis();
                }
                String last = redactor.accept("", true);
                writer.write(last);
                synchronized (output) {
                    output.append(last); livePreview = tail(stripAnsi(stripClixml(output.toString())), PROGRESS_PREVIEW_CHARS);
                    output.setLength(0); output.append(livePreview);
                }
            } catch (IOException e) { if ("running".equals(status)) stop("failed", "命令输出读取失败: " + e.getMessage()); }
        }
        volatile String livePreview;
    }

    /** 使用现有脱敏规则；保留参数名碎片，并持续吞掉跨块的敏感值。 */
    static final class StreamRedactor {
        final List<String> secrets;
        final int retain;
        String pending = "";
        boolean redacting;
        char quote;
        boolean escaped;
        boolean authorization;

        StreamRedactor(List<String> secrets) {
            this.secrets = secrets;
            this.retain = Math.max(128, secrets.stream().mapToInt(String::length).max().orElse(0));
        }

        String accept(String text, boolean finish) {
            if (redacting) {
                int end = 0;
                while (end < text.length()) {
                    char next = text.charAt(end);
                    if (quote != 0 && escaped) { escaped = false; end++; continue; }
                    if (quote != 0 && next == '\\') { escaped = true; end++; continue; }
                    if (delimiter(next)) break;
                    end++;
                }
                if (end == text.length() && !finish) return "";
                redacting = false;
                if (quote != 0 && end < text.length()) end++;
                text = text.substring(end);
            }
            pending += text;
            var matcher = ChatToolExecutor.TOOL_CREDENTIAL.matcher(pending);
            while (matcher.find()) {
                String value = matcher.group(2);
                char first = value.charAt(0);
                boolean quoted = first == '\"' || first == '\'';
                String unescaped = quoted ? value.replaceAll("\\\\.", "") : value;
                if (matcher.end() == pending.length() && !finish
                        && !(quoted && unescaped.length() > 1 && unescaped.charAt(unescaped.length() - 1) == first)) {
                    redacting = true;
                    quote = quoted ? first : 0;
                    escaped = quoted && unescaped.endsWith("\\");
                    authorization = matcher.group(1).toLowerCase(java.util.Locale.ROOT).contains("authorization");
                }
            }
            pending = EnvVarsService.redact(pending, secrets);
            int emit = finish ? pending.length() : Math.max(0, pending.length() - retain);
            String result = pending.substring(0, emit);
            pending = pending.substring(emit);
            return result;
        }

        private boolean delimiter(char value) {
            if (quote != 0) return value == quote;
            return value == ',' || value == ';' || value == '}' || value == ']' || value == '\"' || value == '\''
                    || (authorization ? value == '\n' || value == '\r' : Character.isWhitespace(value));
        }

        String preview() {
            int safe = pending.length();
            for (String secret : secrets) {
                for (int n = Math.min(secret.length() - 1, pending.length()); n > 0; n--) {
                    if (pending.regionMatches(pending.length() - n, secret, 0, n)) {
                        safe = Math.min(safe, pending.length() - n);
                        break;
                    }
                }
            }
            return EnvVarsService.redact(pending.substring(0, safe), secrets);
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : "…" + text.substring(text.length() - max);
    }

    /**
     * 清理 ANSI 转义序列(颜色/光标控制):npm/git 等在管道模式下多数自禁色,
     * 但部分工具(如 FORCE_COLOR、progress bar)仍会输出,污染模型上下文与前端展示。
     * 覆盖 CSI(颜色/光标)与 OSC(标题/超链接)两类。
     */
    static String stripAnsi(String text) {
        if (text == null || text.indexOf('\u001b') < 0) {
            return text;
        }
        // CSI: ESC [ 参数 终止符;OSC: ESC ] ... (BEL 或 ESC 反斜杠)
        return text.replaceAll("\u001b\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)", "");
    }

    /**
     * 清理 PowerShell CLIXML 噪音:stderr 被合并重定向时,PowerShell 会把
     * progress/错误流序列化为 CLIXML(`#< CLIXML` 头 + 一行/多行 {@code <Objs…</Objs>}
     * XML,单行内含完整 XML),与 stdout 交错污染模型上下文(实测 npm 首次运行的
     * 模块准备进度触发)。脚本已设 $ProgressPreference=SilentlyContinue 压制主源,
     * 此处按行兜底:CLIXML 头行与以 {@code <Objs} 开头的行整行丢弃,直到闭合。
     */
    static String stripClixml(String output) {
        if (output == null || !output.contains("#< CLIXML")) {
            return output;
        }
        StringBuilder sb = new StringBuilder();
        boolean inObjs = false;
        for (String line : output.split("\r?\n", -1)) {
            String t = line.strip();
            if (t.equals("#< CLIXML")) {
                continue;
            }
            if (!inObjs && t.startsWith("<Objs")) {
                inObjs = !t.contains("</Objs>"); // 单行闭合则直接跳过,否则进入多行模式
                continue;
            }
            if (inObjs) {
                if (t.contains("</Objs>")) {
                    inObjs = false;
                }
                continue; // XML 主体行整行丢弃
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ---------- 内部实现 ----------

    static String toolName() {
        return isWindows() ? "powershell" : "sh";
    }

    static boolean isCommandTool(String name) {
        return "powershell".equals(name) || "sh".equals(name);
    }

    String runtimeContext() {
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String command : List.of("node", "npm", "npx", "python", "python3", "uv", "uvx", "git", "rg", "curl", "docker")) {
            (McpCommandResolver.resolveCommand(command) == null ? missing : found).add(command);
        }
        return "当前命令运行环境（以本轮信息为准，历史路径和命令不是当前环境快照）：\n"
                + "- 操作系统: " + System.getProperty("os.name") + "；架构: " + System.getProperty("os.arch") + "\n"
                + "- 命令工具: " + toolName() + "；系统已选定执行器，无需也不能传 shell 参数。\n"
                + "- 执行账户: " + System.getProperty("user.name") + "\n"
                + "- 默认工作目录: " + workspaceRoot + "\n"
                + "- Agent 进程 PATH 可定位: " + (found.isEmpty() ? "无" : String.join(", ", found)) + "\n"
                + "- Agent 进程 PATH 未找到: " + (missing.isEmpty() ? "无" : String.join(", ", missing)) + "\n"
                + "- 未找到的运行时可安装后使用;定位到 docker 仅表示客户端存在,不代表守护进程可用。\n"
                + "- 命令在 Agent 所在环境执行；容器内结果不能代表宿主机状态。"
                + "查询工作台服务/容器状态用 environment_status，不要用旧会话结论代替当前查询。";
    }

    /** 解析 cwd:null=工作区;相对=工作区内;绝对=整机。返回 null 表示明显不存在。 */
    private Path resolveCwd(String cwdArg) {
        if (cwdArg == null || cwdArg.isBlank()) {
            return workspaceRoot;
        }
        String raw = cwdArg.trim();
        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            p = workspaceRoot.resolve(raw);
        }
        return p.toAbsolutePath().normalize();
    }

    /**
     * 组装 argv:
     * - powershell:内置 pwsh 7(优先)→ 显式覆盖 → PATH 上的 pwsh;
     *   -EncodedCommand 传 Base64(UTF-16LE)脚本——命令里的引号/换行/美元符
     *   全部免转义;脚本前置 UTF-8 输出编码 + ProgressPreference=SilentlyContinue
     *   (中文 Windows 默认 GBK 乱码;进度流被重定向时会序列化成 CLIXML 噪音)
     * - sh:使用系统 /bin/sh -c,命令作为单个 argv,不依赖登录脚本。
     */
    private List<String> buildArgv(String command) {
        if (!isWindows()) {
            return List.of("/bin/sh", "-c", command);
        }
        String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
                + "$ProgressPreference='SilentlyContinue'; "
                + "$ErrorActionPreference='Stop'; "
                + "$PSNativeCommandUseErrorActionPreference=$true; "
                // stdin EOF 是内部启动闸：先纳入 Job Object，才允许脚本执行用户命令。
                + "$null=[Console]::ReadLine(); " + command;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(powershellExecutable(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-OutputFormat", "Text", "-EncodedCommand", encoded);
    }

    /**
     * 解析 PowerShell 可执行文件(优先内置,保证版本确定、不依赖宿主机):
     * <ol>
     *   <li>显式配置 {@code nora.agent.powershell}(绝对路径或命令名)</li>
     *   <li>内置 pwsh 7:归档解压到缓存目录(首次使用一次性解压,之后命中)</li>
     *   <li>PATH 上的 {@code pwsh}(用户已装 PowerShell 7+)</li>
     *   <li>兜底 {@code powershell.exe}(系统自带 5.1)</li>
     * </ol>
     */
    private String powershellExecutable() {
        if (pwshOverride != null && !pwshOverride.isBlank()) {
            return pwshOverride.trim();
        }
        Path bundled = bundledPwsh();
        if (bundled != null) {
            return bundled.toString();
        }
        if (pwshOnPath()) {
            return "pwsh";
        }
        return "powershell.exe";
    }

    /** 内置 pwsh 可执行文件路径;未配置/归档缺失/解压失败/非 Windows→null(回退 PATH)。 */
    private Path bundledPwsh() {
        // 内置包是 win-x64:仅 Windows 的 PowerShell 执行器使用。
        if (!isWindows() || pwshBundle == null || !Files.isRegularFile(pwshBundle)) {
            return null;
        }
        Path target = pwshBundle.getParent().resolve("pwsh-" + BUNDLED_PWSH_VERSION);
        Path exe = target.resolve("pwsh.exe");
        if (Files.isRegularFile(exe)) {
            return exe;
        }
        // 并发首次解压会撞同一临时目录(名含 PID 不含线程):串行化,双检避免重复解压
        synchronized (EXTRACT_LOCK) {
            if (Files.isRegularFile(exe)) {
                return exe;
            }
            return extractPwsh(pwshBundle, target, exe);
        }
    }

    /** 解压串行化锁(同 JVM 内多会话并发首次调用命令时避免写坏同一临时目录)。 */
    private static final Object EXTRACT_LOCK = new Object();

    /**
     * 解压内置 pwsh 归档到 {@code target}(原子化:先解到临时目录再改名,
     * 避免并发/中断留下半成品)。成功返回 pwsh.exe 路径,失败返回 null(回退 PATH)。
     */
    private Path extractPwsh(Path zip, Path target, Path exe) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try {
            Files.createDirectories(tmp);
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    Path out = tmp.resolve(e.getName()).normalize();
                    if (!out.startsWith(tmp)) {
                        continue; // 防 zip-slip(归档路径逃逸)
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zin.closeEntry();
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 目标可能已被并发线程建好:能读到 exe 即视为成功,否则清理重试一次
                if (!Files.isRegularFile(exe)) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    deleteRecursively(tmp);
                }
            }
            if (Files.isRegularFile(exe)) {
                log.info("terminal: 内置 pwsh {} 已解压到 {}", BUNDLED_PWSH_VERSION, target);
                return exe;
            }
            return null;
        } catch (Exception e) {
            log.warn("terminal: 内置 pwsh 解压失败({}),回退 PATH: {}", zip, e.getMessage());
            deleteRecursively(tmp);
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    /** PATH 上是否有 PowerShell 7(pwsh);启动探测一次并缓存。 */
    private static volatile Boolean pwshOnPath;

    private static boolean pwshOnPath() {
        Boolean cached = pwshOnPath;
        if (cached != null) {
            return cached;
        }
        boolean found;
        try {
            Process p = new ProcessBuilder("pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            found = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            found = false;
        }
        pwshOnPath = found;
        return found;
    }

    /** 杀进程树:先快照后代句柄,Windows 上 taskkill /T,再逐句柄兜底强杀。 */
    private void killProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
            if (isWindows() && process.isAlive()) {
                Process killer = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true).start();
                killer.waitFor(5, TimeUnit.SECONDS);
            }
            for (ProcessHandle h : descendants) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
        } catch (Exception e) {
            log.debug("terminal tree kill failed (pid={}): {}", process.pid(), e.getMessage());
        } finally {
            process.destroyForcibly();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
