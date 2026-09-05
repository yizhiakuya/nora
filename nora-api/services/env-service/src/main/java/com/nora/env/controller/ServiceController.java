package com.nora.env.controller;

import com.nora.common.response.ApiResponse;
import com.nora.env.service.DockerClientService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Environment console endpoints: Docker container list, start/stop/restart,
 * and log tailing (SSE poll stream) per the initiation doc contract.
 */
@RestController
@RequestMapping("/api/environment")
public class ServiceController {

    private final DockerClientService docker;
    private final ExecutorService logExecutor = Executors.newCachedThreadPool();

    public ServiceController(DockerClientService docker) {
        this.docker = docker;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /** Lists containers (managed-label first, all running as fallback). */
    @GetMapping("/services")
    public ApiResponse<List<DockerClientService.ContainerView>> services() {
        return ApiResponse.ok(docker.list());
    }

    /** Starts a container. */
    @PostMapping("/services/{id}/start")
    public ApiResponse<StatusView> start(@PathVariable String id) {
        String result = docker.start(id);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "running", result));
    }

    /** Stops a container. */
    @PostMapping("/services/{id}/stop")
    public ApiResponse<StatusView> stop(@PathVariable String id) {
        String result = docker.stop(id);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "stopped", result));
    }

    /** Restarts a container. */
    @PostMapping("/services/{id}/restart")
    public ApiResponse<StatusView> restart(@PathVariable String id) {
        String result = docker.restart(id);
        return ApiResponse.ok(new StatusView(result.startsWith("ERROR") ? "error" : "running", result));
    }

    /**
     * Log stream: tails {@code tail} recent lines once, then polls every few
     * seconds and pushes new content as SSE {@code log} events.
     *
     * @param service container name or id
     * @param tail    initial lines to fetch
     * @return SSE stream
     */
    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logsStream(@RequestParam("service") String service,
                                 @RequestParam(value = "tail", defaultValue = "100") int tail) {
        SseEmitter emitter = new SseEmitter(300_000L);
        logExecutor.execute(() -> {
            try {
                int lastSent = 0;
                List<String> lines = docker.logs(service, Math.min(tail, 500));
                for (String line : lines) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
                lastSent = lines.size();
                // poll for more lines until the client disconnects or times out
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(5000);
                    List<String> current = docker.logs(service, 500);
                    if (current.size() > lastSent) {
                        for (String line : current.subList(lastSent, current.size())) {
                            emitter.send(SseEmitter.event().name("log").data(line));
                        }
                    }
                    lastSent = current.size();
                }
                emitter.complete();
            } catch (Exception e) {
                // client disconnect or docker error: end the stream quietly
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Action result payload. */
    public record StatusView(String status, String detail) {
    }
}
