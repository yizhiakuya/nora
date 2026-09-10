package com.nora.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(Files.isRegularFile(tmp.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(tmp.resolve("SOUL.md")));
        assertTrue(Files.isRegularFile(tmp.resolve("USER.md")));
        assertTrue(Files.isRegularFile(tmp.resolve("MEMORY.md")));
        assertTrue(Files.isDirectory(tmp.resolve("memory")));
    }

    @Test
    void seedDoesNotOverwriteExistingContent() throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "用户自定义指令");
        AgentWorkspaceService again = new AgentWorkspaceService(tmp.toString());
        assertEquals("用户自定义指令", Files.readString(tmp.resolve("AGENTS.md")));
        assertTrue(again.stats().files() >= 4);
    }

    @Test
    void writeReadAppendRoundTrip() {
        service.write("memory/2026-09-10.md", "- 完成 A");
        service.append("memory/2026-09-10.md", "\n- 完成 B");

        String content = service.read("memory/2026-09-10.md");
        assertTrue(content.contains("- 完成 A"));
        assertTrue(content.contains("- 完成 B"));
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThrows(IllegalArgumentException.class, () -> service.read("C:/Windows/win.ini"));
        assertThrows(IllegalArgumentException.class, () -> service.write("/etc/passwd", "x"));
    }

    @Test
    void rejectsTraversalOutsideRoot() {
        assertThrows(IllegalArgumentException.class, () -> service.read("../../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.write("memory/../../../escape.md", "x"));
        // 反斜杠形式同样拒绝
        assertThrows(IllegalArgumentException.class, () -> service.read("..\\..\\secret.txt"));
    }

    @Test
    void deleteRefusesDirectories() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("memory"));
    }

    @Test
    void deleteRemovesFile() {
        service.write("tmp-note.md", "x");
        service.delete("tmp-note.md");
        assertFalse(Files.exists(tmp.resolve("tmp-note.md")));
    }

    @Test
    void listShowsFilesWithMetadata() {
        service.write("memory/2026-09-10.md", "note");
        List<AgentWorkspaceService.FileEntry> entries = service.list("memory");
        assertEquals(1, entries.size());
        assertEquals("memory/2026-09-10.md", entries.get(0).path());
        assertFalse(entries.get(0).directory());
    }

    @Test
    void appendRejectsWhenOverFileLimit() {
        String big = "x".repeat(AgentWorkspaceService.MAX_FILE_CHARS - 10);
        service.write("big.md", big);
        assertThrows(IllegalArgumentException.class, () -> service.append("big.md", "y".repeat(100)));
    }

    @Test
    void bootstrapPromptInjectsAllFourFilesAndDailyListing() {
        service.write("memory/2026-09-10.md", "daily note");
        String prompt = service.bootstrapPrompt();

        assertTrue(prompt.contains("## AGENTS.md"));
        assertTrue(prompt.contains("## SOUL.md"));
        assertTrue(prompt.contains("## USER.md"));
        assertTrue(prompt.contains("## MEMORY.md"));
        assertTrue(prompt.contains("memory/2026-09-10.md"), "日记清单列出文件名");
        assertTrue(prompt.contains("manage_workspace"), "注入块引用工具名");
    }

    @Test
    void bootstrapTruncatesOversizedFile() throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"),
                "m".repeat(AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS + 2_000));
        String prompt = service.bootstrapPrompt();

        assertTrue(prompt.contains("…(过长截断)"), "超长引导文件应截断并打标记");
        assertTrue(prompt.length() < AgentWorkspaceService.BOOTSTRAP_TOTAL_CHARS + 4_000,
                "总注入量受预算约束");
    }

    @Test
    void todayDailyPathUsesMemoryPrefix() {
        assertTrue(service.todayDailyPath().startsWith("memory/"));
        assertTrue(service.todayDailyPath().endsWith(".md"));
    }

    // ---- 任意路径(区外)语义:工作区=默认 cwd,而非硬沙箱 ----

    @Test
    void resolveAnyMarksInsideAndOutside() {
        AgentWorkspaceService.ResolvedTarget inside = service.resolveAny("USER.md");
        assertTrue(inside.insideWorkspace(), "相对路径=区内");
        assertTrue(inside.path().startsWith(tmp));

        AgentWorkspaceService.ResolvedTarget outside = service.resolveAny("D:/somewhere/other.txt");
        assertTrue(!outside.insideWorkspace(), "绝对路径=区外");
    }

    @Test
    void writeAnyToOutsidePathWorksAndCreatesParentDirs() throws Exception {
        Path outside = tmp.getParent().resolve("nora-ws-test-outside-" + System.nanoTime() + "/a/b/note.md");
        try {
            service.writeAny(outside.toString(), "hello outside");
            assertEquals("hello outside", java.nio.file.Files.readString(outside));
            service.deleteAny(outside.toString());
            assertTrue(!java.nio.file.Files.exists(outside));
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
        assertThrows(IllegalArgumentException.class,
                () -> service.writeAny("C:/Windows/System32/test.txt", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteAny("D:/"));
    }

    @Test
    void readAnyAllowsOutsideRead() throws Exception {
        Path outside = tmp.getParent().resolve("nora-ws-read-test-" + System.nanoTime() + ".txt");
        java.nio.file.Files.writeString(outside, "outside content");
        try {
            assertEquals("outside content", service.readAny(outside.toString()));
        } finally {
            java.nio.file.Files.deleteIfExists(outside);
        }
    }
}
