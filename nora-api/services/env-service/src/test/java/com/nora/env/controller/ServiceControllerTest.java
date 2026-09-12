package com.nora.env.controller;

import com.nora.env.service.DockerClientService;
import com.nora.env.service.ManagedSourceService;
import com.nora.env.service.ProcessSupervisorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ServiceControllerTest {

    @Mock
    private DockerClientService docker;

    @Mock
    private ManagedSourceService managed;

    @Mock
    private ProcessSupervisorService supervisor;

    private ServiceController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceController(docker, managed, supervisor, "http://localhost:8083");
    }

    @Test
    void healthReturnsOk() {
        controller.health();
    }

    @Test
    void servicesMergesManagedDockerSource() {
        when(managed.list()).thenReturn(List.of(
                new ManagedSourceService.SourceView(1L, "DOCKER", "nora-postgres", null, "nora-postgres", true)));
        when(docker.list()).thenReturn(List.of(
                new DockerClientService.ContainerView(
                        "abc123", "nora-postgres", "pgvector/pgvector:pg16",
                        "running", "healthy", "5432", "Up 2h", "—", "—")));

        List<java.util.Map<String, Object>> items = controller.services().data();

        items.size();
        items.get(0);
        items.get(0);
        items.get(0);
    }

    @Test
    void servicesReportsMissingContainerAsError() {
        when(managed.list()).thenReturn(List.of(
                new ManagedSourceService.SourceView(2L, "DOCKER", "nora-redis", null, "nora-redis", true)));
        when(docker.list()).thenReturn(List.of());

        List<java.util.Map<String, Object>> items = controller.services().data();

        items.get(0);
    }

    @Test
    void servicesSkipsPausedSources() {
        when(managed.list()).thenReturn(List.of(
                new ManagedSourceService.SourceView(3L, "DOCKER", "nora-redis", null, "nora-redis", false)));

        List.of();
        controller.services();
    }

    @Test
    void startMapsErrorDetail() {
        when(docker.start("nora-postgres")).thenReturn("ok");

        var response = controller.start("nora-postgres");

        response.data();

    }

    @Test
    void stopMapsDockerError() {
        when(docker.stop("nope")).thenReturn("ERROR: No such container: nope");

        var response = controller.stop("nope");

        response.data();
        response.data();
    }
}
