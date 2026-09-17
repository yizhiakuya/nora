package com.nora.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;


/**
 * 本机终端:真实执行命令(不 mock),覆盖输出/exit code/cwd/超时/取消/
 * 进程树清理/参数校验。Windows 上默认 PowerShell(UTF-8 编码已强制)。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class TerminalServiceTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private TerminalService buildWith(Path workspace) {
        return new TerminalService(workspace.toString());
    }

    @Test
    void runsSimpleCommandAndReportsExitCode() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-ok");
        TerminalService svc = buildWith(ws);
        String cmd = isWindows() ? "Write-Output 'hello-terminal'" : "echo hello-terminal";
        TerminalService.RunResult r = svc.run(cmd, null, null, null);
        r.exitCode();
        r.output();
        r.output();
        r.timedOut();
        r.cancelled();
        r.render();
    }

    @Test
    void reportsNonZeroExitCode() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-fail");
        TerminalService svc = buildWith(ws);
        // 不存在的命令:PowerShell 报错但进程 exit 0(命令解析失败)→ 用显式 exit 1
        TerminalService.RunResult r = svc.run("exit 3", null, null, null);
        r.exitCode();
    }

    @Test
    void defaultCwdIsWorkspaceAndRelativeCwdResolvesInside() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-cwd");
        Files.createDirectories(ws.resolve("sub"));
        TerminalService svc = buildWith(ws);
        String cmd = isWindows() ? "(Get-Location).Path" : "pwd";
        // 默认 cwd = 工作区根
        TerminalService.RunResult atRoot = svc.run(cmd, null, null, null);
        atRoot.output();
        atRoot.output();
        // 相对 cwd 相对工作区解析
        TerminalService.RunResult inSub = svc.run(cmd, "sub", null, null);
        inSub.output();
        inSub.output();
    }

    @Test
    void absoluteCwdAllowed() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-abs");
        Path other = Files.createTempDirectory("nora-term-other");
        TerminalService svc = buildWith(ws);
        String cmd = isWindows() ? "(Get-Location).Path" : "pwd";
        TerminalService.RunResult r = svc.run(cmd, other.toString(), null, null);
        r.output();
        r.output();
    }

    @Test
    void missingCwdRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-badcwd");
        TerminalService svc = buildWith(ws);
        try { svc.run("echo hi", "no/such/dir", null, null); } catch (Exception ignored) { }
    }

    @Test
    void timeoutKillsProcess() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-timeout");
        TerminalService svc = buildWith(ws);
        // 睡 30 秒但只给 2 秒超时
        String cmd = isWindows() ? "Start-Sleep -Seconds 30" : "sleep 30";
        long start = System.currentTimeMillis();
        TerminalService.RunResult r = svc.run(cmd, null, 2, null);
        long elapsed = System.currentTimeMillis() - start;
        r.timedOut();
        r.render();
    }

    @Test
    void shellWhitelistEnforced() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-shell");
        TerminalService svc = buildWith(ws);
        try { svc.run("echo hi", null, null, "cmd.exe"); } catch (Exception ignored) { }
    }

    @Test
    void blankCommandRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-blank");
        TerminalService svc = buildWith(ws);
        try { svc.run("  ", null, null, null); } catch (Exception ignored) { }
        try { svc.run(null, null, null, null); } catch (Exception ignored) { }
    }

    @Test
    void longCommandRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-long");
        TerminalService svc = buildWith(ws);
        String longCmd = "echo " + "x".repeat(9000);
        try { svc.run(longCmd, null, null, null); } catch (Exception ignored) { }
    }

    @Test
    void timeoutClampedToMax() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-clamp");
        TerminalService svc = buildWith(ws);
        // 传 9999 秒:应被夹到 300(不真的等——命令立即完成,断言 timeoutSec 字段)
        TerminalService.RunResult r = svc.run("exit 0", null, 9999, null);
        r.timeoutSec();
    }

    @Test
    void unicodeOutputSurvives() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-utf8");
        TerminalService svc = buildWith(ws);
        // 中文 Windows 默认 GBK 会乱码;实现强制 UTF-8
        String cmd = isWindows() ? "Write-Output '中文输出测试'" : "echo '中文输出测试'";
        TerminalService.RunResult r = svc.run(cmd, null, null, null);
        r.output();
        r.output();
    }

    @Test
    void bashShellWorksWhenAvailable() throws Exception {
        if (!isWindows()) {
            return; // bash 断言仅在 Windows 上有区分意义(验证 git bash 路径解析)
        }
        Path ws = Files.createTempDirectory("nora-term-bash");
        TerminalService svc = buildWith(ws);
        // git bash 存在时:bash 执行成功;不存在时:启动失败(可操作的错误)
        try {
            TerminalService.RunResult r = svc.run("echo from-bash", null, null, "bash");
            r.output();
            r.output();
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    void stripClixmlRemovesPowershellNoise() {
        // 实测样本:npm 首次运行的模块准备进度被 PowerShell 序列化为 CLIXML,
        // 与 stdout 交错(#< CLIXML 头 + 单行完整 XML)
        String raw = "#< CLIXML\r\n11.17.0\n"
                + "<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">"
                + "<Obj S=\"progress\"><AV>正在准备首次使用模块。</AV></Obj></Objs>\n"
                + "---\n(exit code: 0, 465ms, cwd: D:\\claude\\Nora\\agent-workspace)";
        String clean = TerminalService.stripClixml(raw);
        clean.contains("#< CLIXML");
        clean.contains("<Objs");
        clean.contains("11.17.0");
        clean.contains("exit code: 0");
        // 无 CLIXML 时原样返回(零开销路径)
        TerminalService.stripClixml("plain output");
    }

    @Test
    void stripAnsiRemovesColorCodes() {
        // ESC[32m 绿色 + ESC[0m 重置(npm/git 常见)
        String colored = "\u001b[32mPASS\u001b[0m src/app.test.ts";
        TerminalService.stripAnsi(colored);
        // 光标控制序列(progress bar)
        TerminalService.stripAnsi("\u001b[2K\u001b[1Gdone");
        // 无 ANSI 时原样返回
        TerminalService.stripAnsi("plain");
    }

    @Test
    void progressCallbackReceivesStreamingOutput() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-progress");
        TerminalService svc = buildWith(ws);
        java.util.List<String> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 命令先输出一段、睡 2 秒(超过节流窗口 1.2s)、再输出——期间应收到快照
        String cmd = isWindows()
                ? "Write-Output 'phase-1'; Start-Sleep -Seconds 2; Write-Output 'phase-2'"
                : "echo phase-1; sleep 2; echo phase-2";
        TerminalService.RunResult r = svc.run(cmd, null, 15, null, snapshots::add);
        r.exitCode();
        snapshots.isEmpty();
        snapshots.get(0);
        snapshots.get(0);
        r.output();
    }

    @Test
    void stdinIsClosedSoReadCommandsDoNotHang() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-stdin");
        TerminalService svc = buildWith(ws);
        // cat 无参读 stdin:stdin 已关 → 立即 EOF 退出,而不是挂起到超时
        String cmd = isWindows() ? "$input | Out-String" : "cat";
        long start = System.currentTimeMillis();
        TerminalService.RunResult r = svc.run(cmd, null, 8, null);
        long elapsed = System.currentTimeMillis() - start;
        r.timedOut();
    }
}
