import com.nora.agent.service.TerminalService;
import com.nora.agent.service.AgentWorkspaceService;
import com.nora.agent.service.AppSettingStore;
import com.nora.agent.service.CommandExecutionStore;
import com.nora.agent.service.EnvVarsService;
import com.nora.agent.service.SessionProjectService;
import com.nora.agent.service.TurnStreamRegistry;
import com.nora.agent.dto.CommandExecutionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    static JdbcTemplate database(String schema) {
        String url = System.getenv().getOrDefault("NORA_DB_URL", "jdbc:postgresql://localhost:15432/nora");
        return new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv().getOrDefault("NORA_DB_USERNAME", "nora"), System.getenv().getOrDefault("NORA_DB_PASSWORD", "nora")));
    }
    static void waitJournal(Path directory) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (true) {
            try (var files = Files.list(directory)) { if (files.anyMatch(p -> p.toString().endsWith(".json"))) return; }
            if (System.nanoTime() > deadline) throw new IllegalStateException("终态日志未生成");
            Thread.sleep(50);
        }
    }
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length == 2) {
            new CommandExecutionStore(database(args[0]), new ObjectMapper(), new AgentWorkspaceService(args[1])).recover();
            return;
        }
        String schema = "e2e_command_" + UUID.randomUUID().toString().replace("-", "");
        var admin = database("schema_agent");
        Path directory = Files.createTempDirectory("nora-process-e2e-").toRealPath();
        admin.execute("CREATE SCHEMA " + schema);
        var jdbc = database(schema);
        for (String table : List.of("command_execution", "chat_session", "app_setting")) {
            jdbc.execute("CREATE TABLE " + table + " (LIKE schema_agent." + table + " INCLUDING ALL)");
        }
        var mapper = new ObjectMapper();
        var workspace = new AgentWorkspaceService(directory.toString());
        var settings = new AppSettingStore(jdbc, mapper);
        var store = new CommandExecutionStore(jdbc, mapper, workspace);
        terminal = new TerminalService(directory.toString(), "", "",
                settings, new EnvVarsService(settings), store, new TurnStreamRegistry(), new SessionProjectService(jdbc, workspace));
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
            Path journals = directory.resolve(".runtime/command-finalizations");
            String once = windows ? "[IO.File]::AppendAllText('once.txt','x')" : "printf x >> once.txt";
            terminal.takeCompletions(session);
            var pending = start(once + "; " + sleep(2) + "; " + output("DURABLE"), 10);
            jdbc.execute("ALTER TABLE command_execution RENAME TO command_execution_offline");
            waitJournal(journals);
            check(terminal.get(session, pending.executionId()).running(), "数据库提交失败不能宣布命令完成");
            check(terminal.takeCompletions(session).isEmpty(), "待提交结果不发布完成通知");
            jdbc.execute("ALTER TABLE command_execution_offline RENAME TO command_execution");
            var retry = TerminalService.class.getDeclaredMethod("retryFinalizations");
            retry.setAccessible(true); retry.invoke(terminal);
            check(finish(pending).status().equals("completed") && Files.readString(directory.resolve("once.txt")).equals("x"), "恢复只保存结果，不重放命令");
            check(terminal.takeCompletions(session).size() == 1, "保存成功只发布一次完成通知");

            var restart = start(output("RESTART_DURABLE") + "; " + sleep(2), 10);
            jdbc.execute("ALTER TABLE command_execution RENAME TO command_execution_offline");
            waitJournal(journals);
            jdbc.execute("ALTER TABLE command_execution_offline RENAME TO command_execution");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"), "CommandProcessProbe", schema, directory.toString()).inheritIO().start();
            check(child.waitFor() == 0, "新 JVM 恢复终态日志");
            check(store.find(session, restart.executionId()).status().equals("completed"), "重启恢复后保留真实完成状态");
            retry.invoke(terminal);
            try (var files = Files.list(journals)) { check(files.findAny().isEmpty(), "提交成功清理恢复日志"); }
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
            String overLimit = windows ? "1..70 | ForEach-Object { [Console]::Write('x' * 1048576) }"
                    : "head -c 73400320 /dev/zero | tr '\\000' x";
            var capped = finish(start(overLimit, 45));
            Path cappedLog = Path.of(capped.cwd()).resolve(capped.logTarget().substring("workspace:".length()));
            System.out.println("OUTPUT_LIMIT status=" + capped.status() + " bytes=" + Files.size(cappedLog) + " error=" + capped.error());
            check(capped.status().equals("failed") && capped.error().contains("64 MiB")
                    && Files.size(cappedLog) <= 64L * 1024 * 1024, "连续海量输出有界且明确停止");
            System.out.println("TOTAL " + passed);
        } finally {
            terminal.shutdown();
            if (!schema.matches("e2e_command_[a-f0-9]{32}")) throw new IllegalStateException("验收 schema 越界");
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) throw new IllegalStateException("验收工作区越界");
                    Files.delete(path);
                }
            }
        }
    }
}
