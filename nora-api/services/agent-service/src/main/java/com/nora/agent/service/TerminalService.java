package com.nora.agent.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * 本机终端执行(agent 的 run_command 工具):非交互式命令,进程树超时清理。
 *
 * <p>设计对齐 Claude Code / Codex 的终端工具:
 * <ul>
 *   <li>非交互:命令无 TTY,交互式程序(vim/需要输入)会挂起到超时——文档明确</li>
 *   <li>默认 cwd = 工作区(与 manage_workspace 同语义);相对 cwd 相对工作区解析,
 *       绝对 cwd = 整机任意目录(不硬拦——命令本身就能 cd,拦 cwd 是假安全)</li>
 *   <li>Windows 默认 PowerShell(-EncodedCommand 传脚本,规避一切引号/换行转义问题),
 *       强制 UTF-8 输出(中文 Windows 默认 GBK 会乱码);bash 可选(git bash)</li>
 *   <li>超时/取消杀进程树(Windows taskkill /T + 后代句柄兜底),不留孤儿</li>
 *   <li>输出有界(200K 原始上限,展示层 bounded() 再截到 30K);exit code 如实报告</li>
 * </ul>
 *
 * <p>风险由审批把门(RiskClassifier: run_command 一律 HIGH——不做命令白名单,
 * 那是假安全):ASK 全问 / ASSIST 询问 / FULL 自动。
 */
@Service
public class TerminalService {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    /** 默认/最大超时(秒)。 */
    static final int DEFAULT_TIMEOUT_SEC = 60;
    static final int MAX_TIMEOUT_SEC = 300;
    /** 原始输出字节上限(展示层 bounded() 会再截到 30K 字符)。 */
    static final int MAX_RAW_OUTPUT_BYTES = 200_000;
    /** 命令长度上限(字符)。 */
    static final int MAX_COMMAND_CHARS = 8_000;
    /** 支持的 shell 白名单。 */
    static final List<String> SHELLS = List.of("powershell", "bash");
    /** 实时输出回调的节流间隔(ms):执行期间最多每 1.2s 回调一次快照。 */
    static final long PROGRESS_MIN_INTERVAL_MS = 1_200;
    /** 实时输出的缓冲轮询间隔(ms)。 */
    private static final long PROGRESS_POLL_MS = 300;
    /** 实时快照保留的尾部字符数(终端习惯看最新输出)。 */
    static final int PROGRESS_PREVIEW_CHARS = 4_000;

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

    @Autowired
    public TerminalService(
            @Value("${nora.agent.workspace:" + AgentWorkspaceService.DEFAULT_ROOT + "}") String workspacePath,
            @Value("${nora.agent.powershell:}") String pwshOverride,
            @Value("${nora.agent.pwsh-bundle:" + DEFAULT_PWSH_BUNDLE + "}") String pwshBundle,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AppSettingStore appSettingStore) {
        this.workspaceRoot = Path.of(workspacePath).toAbsolutePath().normalize();
        this.pwshOverride = pwshOverride;
        this.pwshBundle = (pwshBundle == null || pwshBundle.isBlank()) ? null : Path.of(pwshBundle).toAbsolutePath().normalize();
        this.appSettingStore = appSettingStore;
    }

    /** 便捷构造:无 appSettingStore(测试/旧调用方)。 */
    public TerminalService(String workspacePath, String pwshOverride, String pwshBundle) {
        this(workspacePath, pwshOverride, pwshBundle, null);
    }

    /** 便捷构造器(测试用):工作区 + 默认内置 pwsh,无显式覆盖。 */
    public TerminalService(String workspacePath) {
        this(workspacePath, "", DEFAULT_PWSH_BUNDLE, null);
    }

    /** 一次命令执行的结果。 */
    public record RunResult(String output, int exitCode, boolean timedOut, boolean cancelled,
                            long durationMs, String cwdUsed, int timeoutSec) {

        /** 渲染为喂给模型的文本:输出 + 一行脚注(exit code/耗时/cwd 或超时/取消标记)。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(output == null || output.isEmpty() ? "(无输出)" : output);
            sb.append("\n---\n");
            if (cancelled) {
                sb.append("(命令已取消,进程已终止)");
            } else if (timedOut) {
                sb.append("(命令超时 ").append(timeoutSec).append("s,进程树已终止;")
                        .append("如确需更长运行请显式传更大的 timeout,上限 ").append(MAX_TIMEOUT_SEC).append("s)");
            } else {
                sb.append("(exit code: ").append(exitCode)
                        .append(", ").append(durationMs).append("ms, cwd: ").append(cwdUsed).append(")");
            }
            return sb.toString();
        }
    }

    /**
     * 运行一条命令。
     *
     * @param command    要执行的命令(必填)
     * @param cwdArg     工作目录;null=工作区根;相对路径相对工作区解析,绝对路径=整机
     * @param timeoutSec 超时(秒);null=默认 60,上限 300
     * @param shell      powershell / bash;null=平台默认(Windows→powershell,其他→bash)
     */
    public RunResult run(String command, String cwdArg, Integer timeoutSec, String shell) {
        return run(command, cwdArg, timeoutSec, shell, null);
    }

    /**
     * 运行一条命令(带实时输出回调)。
     *
     * @param progress 执行期间的输出快照回调(节流 ≤1.2s 一次,尾部 4k 字符,已清
     *                 ANSI/CLIXML);在调用线程上回调——编排层可安全地据此发 SSE
     *                 step 更新(同 id 原地替换)。null=不要实时回调
     */
    public RunResult run(String command, String cwdArg, Integer timeoutSec, String shell,
                         java.util.function.Consumer<String> progress) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("拒绝执行：缺少 command 参数(要运行的命令)");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("拒绝执行：命令超过 " + MAX_COMMAND_CHARS + " 字符上限(当前 " + command.length() + ")");
        }
        int timeout = timeoutSec == null ? DEFAULT_TIMEOUT_SEC : Math.min(Math.max(timeoutSec, 1), MAX_TIMEOUT_SEC);
        String shellName = normalizeShell(shell);
        Path cwd = resolveCwd(cwdArg);
        if (cwd == null || !Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("拒绝执行：工作目录不存在: " + (cwdArg == null ? workspaceRoot : cwdArg)
                    + "(相对路径相对工作区解析;绝对路径=整机)");
        }

        List<String> argv = buildArgv(shellName, command);
        long start = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true);
            // 用户自定义环境变量注入(2026-09-19 设置页「环境变量」真实落地):
            // 设置页存的凭据(GH_TOKEN 等)在命令执行时可用;读取失败静默
            // (命令执行本身不因设置存储故障而失败)
            if (appSettingStore != null) {
                try {
                    com.nora.agent.controller.EnvVarsController.resolveForExecution(appSettingStore)
                            .forEach(pb.environment()::put);
                } catch (Exception ignored) {
                    // 设置读取失败:不注入,继续执行
                }
            }
            process = pb.start();
        } catch (Exception e) {
            throw new IllegalArgumentException("命令启动失败(" + shellName + "): " + e.getMessage()
                    + "——请确认命令存在;" + (isWindows() ? "PowerShell 默认可用,bash 需安装 git bash" : "bash 默认可用"));
        }
        // stdin 立即关闭:无 TTY 语义——读 stdin 的命令(cat/read/需要确认的交互)
        // 得到 EOF 立刻返回,而不是挂起到超时(实测坑:不关 stdin 会阻塞整条命令)
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
            // 关不掉不影响主流程
        }

        // 输出读取:独立线程边读边收集(有上限),避免管道写满阻塞子进程
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    synchronized (buffer) {
                        int room = MAX_RAW_OUTPUT_BYTES - buffer.size();
                        if (room > 0) {
                            buffer.write(chunk, 0, Math.min(n, room));
                        }
                    }
                    // 超出上限后继续排空但丢弃,防止子进程因管道满而卡死
                }
            } catch (Exception ignored) {
                // 进程被杀时读端正常中断
            }
        });

        boolean timedOut = false;
        boolean cancelled = false;
        int exit;
        long deadline = start + timeout * 1000L;
        long lastProgressAt = 0;
        int lastReportedSize = 0;
        try {
            // 轮询等待(而非一次 waitFor(timeout)):期间按节流回调实时输出;
            // 回调在调用线程执行,编排层可安全发 SSE(无跨线程竞争)
            while (true) {
                if (process.waitFor(PROGRESS_POLL_MS, TimeUnit.MILLISECONDS)) {
                    break;
                }
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    timedOut = true;
                    killProcessTree(process);
                    break;
                }
                if (progress != null && now - lastProgressAt >= PROGRESS_MIN_INTERVAL_MS) {
                    int size = bufferSize(buffer);
                    if (size > lastReportedSize) {
                        lastProgressAt = now;
                        lastReportedSize = size;
                        progress.accept(snapshot(buffer));
                    }
                }
            }
        } catch (InterruptedException e) {
            // 用户「停止生成」:编排线程被中断——终止命令,恢复中断标志让上层短路
            cancelled = true;
            killProcessTree(process);
            Thread.currentThread().interrupt();
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exit = process.isAlive() ? -1 : process.exitValue();
        long durationMs = System.currentTimeMillis() - start;
        String output = stripAnsi(stripClixml(buffer.toString(StandardCharsets.UTF_8).stripTrailing()));
        log.info("terminal: shell={} cwd={} exit={} timedOut={} cancelled={} chars={} durationMs={}",
                shellName, cwd, exit, timedOut, cancelled, output.length(), durationMs);
        return new RunResult(output, exit, timedOut, cancelled, durationMs, cwd.toString(), timeout);
    }

    // ---------- 实时快照 ----------

    private static int bufferSize(ByteArrayOutputStream buffer) {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /** 实时预览:全量解码 → 清 ANSI/CLIXML → 取尾部 4k(终端习惯看最新输出)。 */
    private static String snapshot(ByteArrayOutputStream buffer) {
        String full;
        synchronized (buffer) {
            full = buffer.toString(StandardCharsets.UTF_8);
        }
        return tail(stripAnsi(stripClixml(full)), PROGRESS_PREVIEW_CHARS);
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

    private String normalizeShell(String shell) {
        if (shell == null || shell.isBlank()) {
            return isWindows() ? "powershell" : "bash";
        }
        String s = shell.trim().toLowerCase();
        if (!SHELLS.contains(s)) {
            throw new IllegalArgumentException("拒绝执行：shell 只支持 powershell / bash(当前:" + shell + ")");
        }
        return s;
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
     * - bash:-lc 单参数直接传(调用方已把整条命令作为一个 argv)
     */
    private List<String> buildArgv(String shellName, String command) {
        if ("bash".equals(shellName)) {
            return List.of(bashExecutable(), "-lc", command);
        }
        String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
                + "$ProgressPreference='SilentlyContinue'; "
                + "$ErrorActionPreference='Continue'; "
                + command;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(powershellExecutable(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded);
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
        // 内置包是 win-x64:非 Windows 平台直接跳过(用系统 bash/pwsh)
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

    private static String bashExecutable() {
        if (!isWindows()) {
            return "bash";
        }
        // Windows 上裸 "bash" 常被 CreateProcess 解析到 C:\Windows\System32\bash.exe
        // (WSL 转发器,无发行版时报 "execvpe(/bin/bash) failed")——优先 git bash 的
        // 固定安装位置,找不到才退回裸命令(用户自装 git bash 在 PATH 的兜底)
        for (String candidate : List.of(
                "C:/Program Files/Git/bin/bash.exe",
                "C:/Program Files/Git/usr/bin/bash.exe",
                "C:/Program Files (x86)/Git/bin/bash.exe")) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return "bash";
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
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
