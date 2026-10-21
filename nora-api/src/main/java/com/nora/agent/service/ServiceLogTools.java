package com.nora.agent.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.nora.env.service.EnvironmentService;
import com.nora.env.service.ManagedSourceService;

@Service
public class ServiceLogTools {
    private final ManagedSourceService managed;
    private final EnvironmentService environment;

    public ServiceLogTools(ManagedSourceService managed, EnvironmentService environment) {
        this.managed = managed;
        this.environment = environment;
    }

    public List<String> listServices() {
        return managed.list().stream().filter(ManagedSourceService.SourceView::enabled)
                .map(ManagedSourceService.SourceView::name).toList();
    }

    public String readLogs(
            String name,
            int limit) {
        if (name == null || name.isBlank()) return "ERROR: 缺少 service 参数，请先查询纳管源名称";
        var source = managed.list().stream().filter(row -> row.enabled() && name.equals(row.name())).findFirst().orElse(null);
        if (source == null) return "ERROR: unknown service " + name + ". Available: " + listServices();
        var lines = environment.logs(source.id(), Math.clamp(limit, 1, 100));
        return lines.isEmpty() ? "(no recent logs)" : String.join("\n", lines);
    }
}
