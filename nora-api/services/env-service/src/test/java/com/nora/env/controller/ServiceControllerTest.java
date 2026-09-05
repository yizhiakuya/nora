package com.nora.env.controller;

import com.nora.env.service.DockerClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceControllerTest {

    @Mock
    private DockerClientService docker;

    private ServiceController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceController(docker);
    }

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }

    @Test
    void servicesWrapsContainerList() {
        List<DockerClientService.ContainerView> views = List.of(
                new DockerClientService.ContainerView(
                        "abc123", "nora-postgres", "pgvector/pgvector:pg16",
                        "running", "healthy", "5432", "Up 2h", "—", "—"));
        when(docker.list()).thenReturn(views);

        assertEquals(views, controller.services().data());
    }

    @Test
    void startMapsErrorDetail() {
        when(docker.start("nora-postgres")).thenReturn("ok");

        var response = controller.start("nora-postgres");

        assertEquals("running", response.data().status());
        verify(docker).start("nora-postgres");
    }

    @Test
    void stopMapsDockerError() {
        when(docker.stop("nope")).thenReturn("ERROR: No such container: nope");

        var response = controller.stop("nope");

        assertEquals("error", response.data().status());
        assertEquals("ERROR: No such container: nope", response.data().detail());
    }
}
