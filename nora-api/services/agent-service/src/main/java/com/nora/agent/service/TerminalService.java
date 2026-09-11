package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private final Path workspaceRoot;

    public TerminalService(@Value("${nora.agent.workspace:" + AgentWorkspaceService.DEFAULT_ROOT + "}") String workspacePath) {
        this.workspaceRoot = Path.of(workspacePath).toAbsolutePath().normalize();
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
            process = pb.start();
        } catch (Exception e) {
            throw new IllegalArgumentException("命令启动失败(" + shellName + "): " + e.getMessage()
                    + "——请确认命令存在;" + (isWindows() ? "PowerShell 默认可用,bash 需安装 git bash" : "bash 默认可用"));
        }

        // 输出读取:独立线程边读边收集(有上限),避免管道写满阻塞子进程
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    int room = MAX_RAW_OUTPUT_BYTES - buffer.size();
                    if (room > 0) {
                        buffer.write(chunk, 0, Math.min(n, room));
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
        try {
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                timedOut = true;
                killProcessTree(process);
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
        String output = stripClixml(buffer.toString(StandardCharsets.UTF_8).stripTrailing());
        log.info("terminal: shell={} cwd={} exit={} timedOut={} cancelled={} chars={} durationMs={}",
                shellName, cwd, exit, timedOut, cancelled, output.length(), durationMs);
        return new RunResult(output, exit, timedOut, cancelled, durationMs, cwd.toString(), timeout);
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

    // ---------- internals ----------

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
     * - powershell:-EncodedCommand 传 Base64(UTF-16LE)脚本——命令里的引号/换行/美元符
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
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded);
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
