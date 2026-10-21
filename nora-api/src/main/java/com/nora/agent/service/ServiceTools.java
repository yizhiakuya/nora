package com.nora.agent.service;

import org.springframework.stereotype.Service;

import com.nora.env.service.ManagedSourceService;

@Service
public class ServiceTools {
    private final ManagedSourceService managed;
    private final EnvironmentTools environment;
    private final ServiceLogTools logs;
    private final ContainerTools controls;

    public ServiceTools(ManagedSourceService managed, EnvironmentTools environment, ServiceLogTools logs, ContainerTools controls) {
        this.managed = managed;
        this.environment = environment;
        this.logs = logs;
        this.controls = controls;
    }

    public String status() { return environment.statusSummary(); }

    public String logs(long id, Integer limit) { return logs.readLogs(name(id), limit == null ? 50 : limit); }

    public ChatToolExecutor.ToolOutcome control(long id, String action) {
        return controls.control(name(id), ContainerTools.Action.valueOf(action));
    }

    private String name(long id) {
        return managed.list().stream().filter(source -> source.id() == id).map(ManagedSourceService.SourceView::name)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("纳管源不存在: " + id));
    }

    public String register(String kind, String name, String fileLogPath, String containerName, String command, String workDir) {
        var source = managed.create(kind, name, fileLogPath, containerName, command, workDir);
        return "已注册纳管源 id=" + source.id() + " name=" + source.name() + " kind=" + source.kind();
    }

    public String setEnabled(long id, boolean enabled) {
        return managed.setEnabled(id, enabled) ? "已" + (enabled ? "恢复" : "暂停") + "纳管源 id=" + id
                : "ERROR: 纳管源不存在: " + id;
    }

    public String remove(long id) {
        return managed.delete(id) ? "已删除纳管源 id=" + id + "(容器/文件本身不受影响)" : "ERROR: 纳管源不存在: " + id;
    }

    public String list() {
        var sources = managed.list();
        if (sources.isEmpty()) return "(尚无纳管源)";
        return sources.stream().map(row -> "id=" + row.id() + " | " + row.kind() + " | " + row.name() + " | "
                + switch (row.kind()) { case "FILE" -> row.fileLogPath(); case "DOCKER" -> row.containerName(); default -> row.command(); }
                + " | " + (row.enabled() ? "enabled" : "paused")).collect(java.util.stream.Collectors.joining("\n"));
    }

    Long resolveId(String target) {
        String value = target.trim();
        return managed.list().stream().filter(source -> value.equals(Long.toString(source.id()))
                || value.equalsIgnoreCase(source.name())).map(ManagedSourceService.SourceView::id).findFirst().orElse(null);
    }
}
