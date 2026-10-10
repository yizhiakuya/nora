import com.nora.agent.service.TerminalService;
import com.nora.agent.dto.CommandExecutionDto;
import java.nio.file.*;
import java.util.*;

/** E2E：直接运行真实 OS 进程。行为校验留在实测入口，不放进单元冒烟。 */
public class CommandProcessProbe {
    static boolean windows = System.getProperty("os.name").startsWith("Windows");
    static TerminalService terminal;
    static String session = "command-process-e2e-" + UUID.randomUUID();
    static int passed;
    static void check(boolean ok, String name) {
        if (!ok) throw new IllegalStateException("FAIL " + name);
        System.out.println("PASS " + name); passed++;
    }
    static CommandExecutionDto start(String command, int timeout) {
        return terminal.start(session, null, command, null, timeout, "进程实测");
    }
    static CommandExecutionDto finish(CommandExecutionDto execution) {
        do { execution = terminal.await(session, execution.executionId(), 1000, null, () -> false); } while (execution.running());
        return execution;
    }
    static String output(String text) { return windows ? "[Console]::Write('" + text + "')" : "printf '%s' '" + text + "'"; }
    static String sleep(int seconds) { return windows ? "Start-Sleep -Seconds " + seconds : "sleep " + seconds; }
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        terminal = new TerminalService(Files.createTempDirectory("nora-process-e2e-").toString());
        try {
            var shortJob = finish(start(output("中文🙂SHORT"), 10));
            check(shortJob.exitCode() == 0 && shortJob.output().contains("中文🙂SHORT"), "UTF8 与真实退出码");
            var job = start(output("FIRST") + "; " + sleep(2) + "; " + output("SECOND") + "; " + sleep(2), 15);
            long begin = System.nanoTime();
            var yielded = terminal.await(session, job.executionId(), 0, null, () -> false);
            check(yielded.running() && (System.nanoTime() - begin) / 1000000 < 250, "后台立即返回，同一执行 ID");
            Thread.sleep(1000);
            check(terminal.get(session, job.executionId()).output().contains("FIRST"), "无换行输出在结束前可见");
            check(finish(job).output().contains("SECOND"), "最终输出完整刷新");
            String flood = windows ? "[Console]::Write('x' * 220000)" : "head -c 220000 /dev/zero | tr '\\000' x";
            var large = finish(start(flood + "; " + sleep(1) + "; " + output("LATE_MARKER"), 20));
            check(large.output().contains("LATE_MARKER") && large.output().length() <= 4001, "大量输出保持尾部和内存上限");
            Path fullLog = Path.of(large.cwd()).resolve(large.logTarget().substring("workspace:".length()));
            check(Files.size(fullLog) > 220000 && Files.readString(fullLog).endsWith("LATE_MARKER"), "完整日志可读取中间内容");
            var secret = finish(start(output("api_key=secret") + "; " + sleep(1) + "; " + output("value end"), 10));
            Path secretLog = Path.of(secret.cwd()).resolve(secret.logTarget().substring("workspace:".length()));
            check(!secret.output().contains("secretvalue") && !Files.readString(secretLog).contains("secretvalue"), "跨输出块脱敏");
            var timeout = finish(start(sleep(30), 2));
            check(timeout.status().equals("timed_out") && timeout.elapsedMs() < 5000, "安静命令也按总时限终止");
            var slow = start(output("SLOW_CALLBACK") + "; " + sleep(30), 2);
            Thread callback = Thread.ofVirtual().start(() -> terminal.await(session, slow.executionId(), 10000, ignored -> {
                try { Thread.sleep(3500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, () -> false));
            Thread.sleep(2500);
            check(terminal.get(session, slow.executionId()).status().equals("timed_out"), "慢输出订阅者不延长进程超时");
            callback.join(5000);
            String overLimit = windows ? "1..70 | ForEach-Object { [Console]::Write('x' * 1048576) }"
                    : "head -c 73400320 /dev/zero | tr '\\000' x";
            var capped = finish(start(overLimit, 45));
            Path cappedLog = Path.of(capped.cwd()).resolve(capped.logTarget().substring("workspace:".length()));
            check(capped.status().equals("failed") && capped.error().contains("64 MiB")
                    && Files.size(cappedLog) <= 64L * 1024 * 1024, "连续海量输出有界且明确停止");
            var failure = finish(start("exit 7", 10));
            check(failure.status().equals("failed") && failure.exitCode() == 7, "非零退出保持原始状态");
            var explicit = finish(start("exit 0", 9999));
            check(explicit.timeoutSeconds() == 9999, "显式超时不静默裁剪");
            var cancel = start(sleep(30), 40);
            terminal.stop(session, cancel.executionId());
            check(finish(cancel).status().equals("cancelled"), "任务独立停止");
            boolean rejected = false;
            try { terminal.get("another-session", cancel.executionId()); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "会话隔离");
            List<CommandExecutionDto> four = new ArrayList<>();
            for (int i = 0; i < 4; i++) four.add(start(sleep(30), 40));
            rejected = false;
            try { start("exit 0", 10); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "并发上限覆盖已返回的后台进程");
            for (var item : four) { terminal.stop(session, item.executionId()); finish(item); }
            check(finish(start("exit 0", 10)).exitCode() == 0, "退出后释放并发名额");
            String detached = windows
                ? "$p=Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 30' -PassThru; [Console]::Write($p.Id); exit 0"
                : "sleep 30 & child=$!; printf '%s' \"$child\"; exit 0";
            var parent = finish(start(detached, 10));
            long childPid = Long.parseLong(parent.output().trim());
            Thread.sleep(500);
            boolean alive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
            if (alive) ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            check(!alive, "父 shell 先退出仍清理子进程");
            System.out.println("TOTAL " + passed);
        } finally { terminal.shutdown(); }
    }
}
