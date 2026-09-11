package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机终端:真实执行命令(不 mock),覆盖输出/exit code/cwd/超时/取消/
 * 进程树清理/参数校验。Windows 上默认 PowerShell(UTF-8 编码已强制)。
 */
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
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("hello-terminal"), "输出包含命令结果: " + r.output());
        assertFalse(r.timedOut());
        assertFalse(r.cancelled());
        assertTrue(r.render().contains("exit code: 0"), "渲染含 exit code");
    }

    @Test
    void reportsNonZeroExitCode() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-fail");
        TerminalService svc = buildWith(ws);
        // 不存在的命令:PowerShell 报错但进程 exit 0(命令解析失败)→ 用显式 exit 1
        TerminalService.RunResult r = svc.run("exit 3", null, null, null);
        assertEquals(3, r.exitCode(), "非零退出码如实报告");
    }

    @Test
    void defaultCwdIsWorkspaceAndRelativeCwdResolvesInside() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-cwd");
        Files.createDirectories(ws.resolve("sub"));
        TerminalService svc = buildWith(ws);
        String cmd = isWindows() ? "(Get-Location).Path" : "pwd";
        // 默认 cwd = 工作区根
        TerminalService.RunResult atRoot = svc.run(cmd, null, null, null);
        assertTrue(atRoot.output().toLowerCase().contains(ws.getFileName().toString().toLowerCase()),
                "默认 cwd 是工作区: " + atRoot.output());
        // 相对 cwd 相对工作区解析
        TerminalService.RunResult inSub = svc.run(cmd, "sub", null, null);
        assertTrue(inSub.output().toLowerCase().contains("sub"), "相对 cwd 落在工作区内: " + inSub.output());
    }

    @Test
    void absoluteCwdAllowed() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-abs");
        Path other = Files.createTempDirectory("nora-term-other");
        TerminalService svc = buildWith(ws);
        String cmd = isWindows() ? "(Get-Location).Path" : "pwd";
        TerminalService.RunResult r = svc.run(cmd, other.toString(), null, null);
        assertTrue(r.output().toLowerCase().contains(other.getFileName().toString().toLowerCase()),
                "绝对 cwd 生效: " + r.output());
    }

    @Test
    void missingCwdRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-badcwd");
        TerminalService svc = buildWith(ws);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.run("echo hi", "no/such/dir", null, null));
        assertTrue(e.getMessage().contains("工作目录不存在"), "错误指向 cwd");
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
        assertTrue(r.timedOut(), "超时标记");
        assertTrue(elapsed < 15_000, "超时后及时返回(实际 " + elapsed + "ms)");
        assertTrue(r.render().contains("超时"), "渲染含超时说明");
    }

    @Test
    void shellWhitelistEnforced() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-shell");
        TerminalService svc = buildWith(ws);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.run("echo hi", null, null, "cmd.exe"));
        assertTrue(e.getMessage().contains("powershell / bash"), "shell 白名单错误可操作");
    }

    @Test
    void blankCommandRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-blank");
        TerminalService svc = buildWith(ws);
        assertThrows(IllegalArgumentException.class, () -> svc.run("  ", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> svc.run(null, null, null, null));
    }

    @Test
    void longCommandRejected() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-long");
        TerminalService svc = buildWith(ws);
        String longCmd = "echo " + "x".repeat(9000);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.run(longCmd, null, null, null));
        assertTrue(e.getMessage().contains("上限"));
    }

    @Test
    void timeoutClampedToMax() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-clamp");
        TerminalService svc = buildWith(ws);
        // 传 9999 秒:应被夹到 300(不真的等——命令立即完成,断言 timeoutSec 字段)
        TerminalService.RunResult r = svc.run("exit 0", null, 9999, null);
        assertEquals(TerminalService.MAX_TIMEOUT_SEC, r.timeoutSec(), "超时上限 300s");
    }

    @Test
    void unicodeOutputSurvives() throws Exception {
        Path ws = Files.createTempDirectory("nora-term-utf8");
        TerminalService svc = buildWith(ws);
        // 中文 Windows 默认 GBK 会乱码;实现强制 UTF-8
        String cmd = isWindows() ? "Write-Output '中文输出测试'" : "echo '中文输出测试'";
        TerminalService.RunResult r = svc.run(cmd, null, null, null);
        assertTrue(r.output().contains("中文输出测试"), "中文输出不乱码: " + r.output());
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
            assertTrue(r.output().contains("from-bash"), "bash 输出: " + r.output());
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("命令启动失败"), "无 bash 时给出可操作错误");
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
        assertFalse(clean.contains("#< CLIXML"), "CLIXML 头已清除");
        assertFalse(clean.contains("<Objs"), "XML 主体已清除");
        assertTrue(clean.contains("11.17.0"), "真实输出保留");
        assertTrue(clean.contains("exit code: 0"), "脚注保留");
        // 无 CLIXML 时原样返回(零开销路径)
        assertEquals("plain output", TerminalService.stripClixml("plain output"));
    }
}
