package com.nora.agent.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 当前进程可见的项目绑定；不改变进程权限或远程执行目标。 */
@Service
public class SessionProjectService {
    private final JdbcTemplate jdbc;
    private final AgentWorkspaceService workspace;
    private final WorkspaceBootstrap workspaceBootstrap;

    public SessionProjectService(JdbcTemplate jdbc, AgentWorkspaceService workspace, WorkspaceBootstrap workspaceBootstrap) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.workspaceBootstrap = workspaceBootstrap;
    }

    public Path root(String sessionId) {
        if (sessionId == null) return workspace.root();
        List<String> rows = jdbc.query("SELECT project_root FROM chat_session WHERE id=? AND deleted_at IS NULL",
                (rs, row) -> rs.getString(1), sessionId);
        return rows.isEmpty() || rows.getFirst() == null ? workspace.root() : Path.of(rows.getFirst());
    }

    public ProjectView view(String sessionId) {
        Path root = root(sessionId);
        return new ProjectView(root.toString(), System.getProperty("os.name"),
                Files.exists(Path.of("/.dockerenv")) ? "当前 Agent 容器" : "当前 Agent 进程所在系统",
                workspaceBootstrap.bootstrap(root).files());
    }

    public ProjectView bind(String sessionId, String directory) {
        Path path = readableDirectory(directory == null || directory.isBlank() ? workspace.root() : Path.of(directory.trim()));
        if (jdbc.update("UPDATE chat_session SET project_root=? WHERE id=? AND deleted_at IS NULL", path.toString(), sessionId) != 1)
            throw new IllegalArgumentException("session not found");
        return view(sessionId);
    }

    private Path readableDirectory(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("项目目录必须是当前执行环境中的绝对路径");
        try { path = path.toRealPath(); }
        catch (IOException e) { throw new IllegalArgumentException("项目目录不可访问: " + path); }
        if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalArgumentException("项目目录不存在或不可读: " + path);
        return path;
    }

    public DirectoryView directories(String sessionId, String directory) {
        Path path = readableDirectory(directory == null ? root(sessionId)
                : directory.isBlank() ? workspace.root() : Path.of(directory.trim()));
        var roots = new ArrayList<String>();
        FileSystems.getDefault().getRootDirectories().forEach(root -> roots.add(root.toString()));
        try (var stream = Files.list(path)) {
            var children = stream.filter(Files::isDirectory).limit(501)
                    .map(child -> new DirectoryEntry(child.getFileName().toString(), child.toString()))
                    .sorted(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER)).toList();
            return new DirectoryView(path.toString(), path.getParent() == null ? null : path.getParent().toString(),
                    workspace.root().toString(), roots, children.subList(0, Math.min(500, children.size())), children.size() > 500);
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("目录列举失败: " + e.getMessage());
        }
    }

    public record DirectoryEntry(String name, String path) {}
    public record DirectoryView(String path, String parent, String defaultRoot, List<String> roots,
                                List<DirectoryEntry> directories, boolean truncated) {}

    public record ProjectView(String projectRoot, String platform, String environment,
                              List<WorkspaceBootstrap.BootstrapFileInfo> rules) {}
}
