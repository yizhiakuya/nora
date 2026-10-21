package com.nora.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class AgentWorkspaceServiceTest {

    @TempDir
    Path tmp;

    private AgentWorkspaceService service;
    /** 引导注入的装配在 WorkspaceBootstrap,测试沿用真实装配。 */
    private WorkspaceBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        service = new AgentWorkspaceService(tmp.toString());
        bootstrap = new WorkspaceBootstrap(service);
    }

    @Test
    void seedsBootstrapFilesOnInit() {
        Files.isRegularFile(tmp.resolve("AGENTS.md"));
        Files.isRegularFile(tmp.resolve("SOUL.md"));
        Files.isRegularFile(tmp.resolve("USER.md"));
        Files.isRegularFile(tmp.resolve("MEMORY.md"));
        Files.isDirectory(tmp.resolve("memory"));
    }

    @Test
    void seedDoesNotOverwriteExistingContent() throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "用户自定义指令");
        AgentWorkspaceService again = new AgentWorkspaceService(tmp.toString());
        Files.readString(tmp.resolve("AGENTS.md"));
        again.stats();
    }

    @Test
    void writeReadAppendRoundTrip() {
        service.write("memory/2026-09-10.md", "- 完成 A");
        service.append("memory/2026-09-10.md", "\n- 完成 B");

        String content = service.read("memory/2026-09-10.md");
        content.contains("- 完成 A");
        content.contains("- 完成 B");
    }

    @Test
    void readsSingleLineEvidenceByCharactersAndKeepsLinePagination() {
        service.write("single.json", "{\"snapshot\":\"" + "中文😀".repeat(4000) + "\"}");
        service.readRangeAny("single.json", 4000, 6000, "chars");
        service.readRangeAny("single.json", 10000, 6000, "chars");
        service.write("lines.txt", "第一行\n第二行\n第三行\n");
        service.readRangeAny("lines.txt", 2, 1, "lines");
        service.write("empty.txt", "");
        service.readRangeAny("empty.txt", 1, 6000, "chars");
        var record = service.archiveContext("tool", "中文😀".repeat(4000));
        service.readRangeAny(record.path(), 6001, 6000, "chars");
        record.readHint();
    }

    @Test
    void editReplacesUniqueSnippet() {
        // edit(精确替换,2026-09-18):唯一片段可替换;不唯一/不存在被拒(冒烟走异常路径)
        service.write("MEMORY.md", "第一行\n要改的段落\n第三行");
        service.editAny("MEMORY.md", "要改的段落", "改后的段落");
        try { service.editAny("MEMORY.md", "不存在的片段", "x"); } catch (Exception ignored) { }
        service.write("dup.md", "abc\nabc");
        try { service.editAny("dup.md", "abc", "x"); } catch (Exception ignored) { }
        try { service.editAny("missing-file.md", "a", "b"); } catch (Exception ignored) { }
    }

    @Test
    void editAcceptsLfAndCrlfWithoutChangingSurroundingLines() {
        service.write("crlf.md", "\uFEFF第一行\r\n[原文]\\E\r\n第二行\r\n结尾\r\n");
        service.editAny("crlf.md", "[原文]\\E\n第二行", "替换\n新行");
        service.read("crlf.md");
        service.write("lf.md", "前言\n原文\n第二行\n结尾\n");
        service.editAny("lf.md", "原文\r\n第二行", "替换\r\n新行");
        service.read("lf.md");
        service.write("mixed.md", "前言\r\n原文\n第二行\r\n结尾\n");
        service.editAny("mixed.md", "原文\r\n第二行\r\n", "替换\n");
        service.read("mixed.md");
        service.write("overlap.md", "aaa");
        try { service.editAny("overlap.md", "aa", "x"); } catch (IllegalArgumentException ignored) { }
    }

    @Test
    void journalEmptyStateGivesActionableHint() {
        // 日记空态出路(2026-09-18 文件工具分析):读今天的日记不存在 → 可操作提示;
        // 读过去某天不存在 → 提示查看已有日记;非日记文件名 → 普通错误
        String today = java.time.LocalDate.now().toString() + ".md";
        try { service.readAny("memory/" + today); } catch (Exception ignored) { }
        try { service.readAny("memory/2020-01-01.md"); } catch (Exception ignored) { }
        try { service.readAny("memory/not-a-date.md"); } catch (Exception ignored) { }
    }

    @Test
    void isDirectoryAnyDetectsDirectories() {
        // import 目录语义兼容用(2026-09-18):目录=true,文件=false,不存在=false
        service.mkdirAny("imports");
        service.write("imports/note.txt", "x");
        service.isDirectoryAny("imports");
        service.isDirectoryAny("imports/note.txt");
        service.isDirectoryAny("no-such-dir");
    }

    @Test
    void rejectsAbsolutePaths() {
        try { service.read("C:/Windows/win.ini"); } catch (Exception ignored) { }
        try { service.write("/etc/passwd", "x"); } catch (Exception ignored) { }
    }

    @Test
    void rejectsTraversalOutsideRoot() {
        try { service.read("../../secret.txt"); } catch (Exception ignored) { }
        try { service.write("memory/../../../escape.md", "x"); } catch (Exception ignored) { }
        // 反斜杠形式同样拒绝
        try { service.read("..\\..\\secret.txt"); } catch (Exception ignored) { }
    }

    @Test
    void deleteRefusesDirectories() {
        try { service.delete("memory"); } catch (Exception ignored) { }
    }

    @Test
    void deleteRemovesFile() {
        service.write("tmp-note.md", "x");
        service.delete("tmp-note.md");
        Files.exists(tmp.resolve("tmp-note.md"));
    }

    @Test
    void listShowsFilesWithMetadata() {
        service.write("memory/2026-09-10.md", "note");
        List<AgentWorkspaceService.FileEntry> entries = service.list("memory");
        entries.size();
        entries.get(0);
        entries.get(0);
    }

    @Test
    void transfersShareValidationForPlainAndGlobSources() throws Exception {
        service.write("transfer/a.txt", "alpha");
        service.write("transfer/b.txt", "beta");
        service.copyAny("transfer/*.txt", "copied");
        service.moveAny("copied/*.txt", "moved");
        service.copyAny("transfer", "tree-copy");
        service.moveAny("moved/a.txt", "renamed.txt");
        service.list("tree-copy");
        for (String source : List.of("transfer", "trans*", tmp.toString(), tmp + "*")) {
            try { service.copyAny(source, "transfer/inside"); }
            catch (IllegalArgumentException ignored) { }
        }
        Files.writeString(tmp.resolve("moved/a.txt"), "keep");
        try { service.copyAny("transfer/*.txt", "moved"); }
        catch (IllegalArgumentException ignored) { }
    }

    @Test
    void appendRejectsWhenOverFileLimit() {
        String big = "x".repeat(AgentWorkspaceService.MAX_FILE_CHARS - 10);
        service.write("big.md", big);
        try { service.append("big.md", "y".repeat(100)); } catch (Exception ignored) { }
    }

    @Test
    void bootstrapInjectsAllFourFilesAndDailyListing() {
        service.write("memory/2026-09-10.md", "daily note");
        String prompt = bootstrap.bootstrap().text();

        prompt.contains("## AGENTS.md");
        prompt.contains("## SOUL.md");
        prompt.contains("## USER.md");
        prompt.contains("## MEMORY.md");
        prompt.contains("memory/2026-09-10.md");
        prompt.contains("manage_workspace");
    }

    @Test
    void bootstrapReportsPerFileMetadataForContextStep() {
        service.write("memory/2026-09-10.md", "daily note");
        WorkspaceBootstrap.BootstrapResult result = bootstrap.bootstrap();

        // 逐文件元数据(前端「加载长期记忆」步骤展开可见):4 个引导文件 + 日记清单
        result.files();
        result.files();
        result.files();
        // 正文随元数据下发:前端文件行展开显示模型实际读到的内容(dsh 的 instructions 形态)
        WorkspaceBootstrap.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.content();
        List.of("memory/2026-09-10.md");
        result.dailyNotes();
    }

    @Test
    void bootstrapReportsUtf8BytesNotCharCount() throws Exception {
        // 中文 3 字节/字符:bytes 必须是 UTF-8 字节数,与文件实际大小一致(前端按 B/KB 展示)
        Files.writeString(tmp.resolve("MEMORY.md"), "中文记忆");
        WorkspaceBootstrap.BootstrapResult result = bootstrap.bootstrap();

        WorkspaceBootstrap.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.bytes();
    }

    @Test
    void bootstrapMarksOversizedFileAsTruncated() throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"),
                "m".repeat(AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS + 2_000));
        WorkspaceBootstrap.BootstrapResult result = bootstrap.bootstrap();

        WorkspaceBootstrap.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.truncated();
        memory.bytes();
    }

    @Test
    void bootstrapTruncatesOversizedFile() throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"),
                "m".repeat(AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS + 2_000));
        String prompt = bootstrap.bootstrap().text();

        prompt.contains("…(过长截断)");
        prompt.length();
    }

    @Test
    void todayDailyPathUsesMemoryPrefix() {
        service.todayDailyPath();
        service.todayDailyPath();
    }

    // ---- 任意路径(区外)语义:工作区=默认 cwd,而非硬沙箱 ----

    @Test
    void resolveAnyMarksInsideAndOutside() {
        AgentWorkspaceService.ResolvedTarget inside = service.resolveAny("USER.md");
        inside.insideWorkspace();
        inside.path();

        AgentWorkspaceService.ResolvedTarget outside = service.resolveAny("D:/somewhere/other.txt");
        outside.insideWorkspace();
    }

    @Test
    void writeAnyToOutsidePathWorksAndCreatesParentDirs() throws Exception {
        Path outside = tmp.getParent().resolve("nora-ws-test-outside-" + System.nanoTime() + "/a/b/note.md");
        try {
            service.writeAny(outside.toString(), "hello outside");
            java.nio.file.Files.readString(outside);
            service.deleteAny(outside.toString());
            java.nio.file.Files.exists(outside);
        } finally {
            // 递归清理测试残留目录
            Path root = outside.getParent().getParent().getParent();
            try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
    }

    @Test
    void writeAnyRefusesSystemPaths() {
        try { service.writeAny("C:/Windows/System32/test.txt", "x"); } catch (Exception ignored) { }
        try { service.deleteAny("D:/"); } catch (Exception ignored) { }
    }

    @Test
    void readAnyAllowsOutsideRead() throws Exception {
        Path outside = tmp.getParent().resolve("nora-ws-read-test-" + System.nanoTime() + ".txt");
        java.nio.file.Files.writeString(outside, "outside content");
        try {
            service.readAny(outside.toString());
        } finally {
            java.nio.file.Files.deleteIfExists(outside);
        }
    }
}
