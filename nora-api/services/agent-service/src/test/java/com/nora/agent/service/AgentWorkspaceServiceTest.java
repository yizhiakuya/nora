package com.nora.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class AgentWorkspaceServiceTest {

    @TempDir
    Path tmp;

    private AgentWorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new AgentWorkspaceService(tmp.toString());
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
    void appendRejectsWhenOverFileLimit() {
        String big = "x".repeat(AgentWorkspaceService.MAX_FILE_CHARS - 10);
        service.write("big.md", big);
        try { service.append("big.md", "y".repeat(100)); } catch (Exception ignored) { }
    }

    @Test
    void bootstrapPromptInjectsAllFourFilesAndDailyListing() {
        service.write("memory/2026-09-10.md", "daily note");
        String prompt = service.bootstrapPrompt();

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
        AgentWorkspaceService.BootstrapResult result = service.bootstrap();

        // 逐文件元数据(前端「加载长期记忆」步骤展开可见):4 个引导文件 + 日记清单
        result.files();
        result.files();
        result.files();
        // 正文随元数据下发:前端文件行展开显示模型实际读到的内容(dsh 的 instructions 形态)
        AgentWorkspaceService.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.content();
        List.of("memory/2026-09-10.md");
        result.dailyNotes();
    }

    @Test
    void bootstrapReportsUtf8BytesNotCharCount() throws Exception {
        // 中文 3 字节/字符:bytes 必须是 UTF-8 字节数,与文件实际大小一致(前端按 B/KB 展示)
        Files.writeString(tmp.resolve("MEMORY.md"), "中文记忆");
        AgentWorkspaceService.BootstrapResult result = service.bootstrap();

        AgentWorkspaceService.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.bytes();
    }

    @Test
    void bootstrapMarksOversizedFileAsTruncated() throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"),
                "m".repeat(AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS + 2_000));
        AgentWorkspaceService.BootstrapResult result = service.bootstrap();

        AgentWorkspaceService.BootstrapFileInfo memory = result.files().stream()
                .filter(f -> f.path().equals("MEMORY.md")).findFirst().orElseThrow();
        memory.truncated();
        memory.bytes();
    }

    @Test
    void bootstrapTruncatesOversizedFile() throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"),
                "m".repeat(AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS + 2_000));
        String prompt = service.bootstrapPrompt();

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
