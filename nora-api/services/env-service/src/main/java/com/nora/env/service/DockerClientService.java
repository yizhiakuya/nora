package com.nora.env.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker facade. v1 shells out to the {@code docker} CLI (simple, no extra
 * dependencies, works with Docker Desktop's named pipe); the JSON output
 * mode keeps parsing stable. A later phase can swap in the Docker HTTP API.
 */
@Service
public class DockerClientService {

    private static final Logger log = LoggerFactory.getLogger(DockerClientService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One managed container as consumed by the frontend ServiceInstance. */
    public record ContainerView(
            String id,
            String name,
            String image,
            String status,       // running / stopped / error
            String health,       // healthy / degraded / down
            String port,
            String uptime,
            String cpu,
            String memory) {
    }

    /** Lists containers (running + exited) as JSON via docker ps. */
    public List<ContainerView> list() {
        try {
            String raw = exec("docker", "ps", "-a",
                    "--filter", "label=nora.managed=true",
                    "--format", "{{json .}}");
            List<ContainerView> result = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                result.add(toView(node));
            }
            if (result.isEmpty()) {
                // no labeled containers yet: fall back to all running containers
                return listAll();
            }
            return result;
        } catch (Exception e) {
            log.warn("docker ps failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContainerView> listAll() throws Exception {
        String raw = exec("docker", "ps", "--format", "{{json .}}");
        List<ContainerView> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            result.add(toView(objectMapper.readTree(line)));
        }
        return result;
    }

    private ContainerView toView(JsonNode node) {
        String state = node.path("State").asText("unknown");
        String status = switch (state) {
            case "running" -> "running";
            case "exited", "dead", "created" -> "stopped";
            default -> "error";
        };
        String health = "running".equals(status) ? "healthy" : "down";
        if (status.equals("running") && node.path("Status").asText("").contains("unhealthy")) {
            health = "degraded";
        }
        return new ContainerView(
                node.path("ID").asText(),
                node.path("Names").asText(),
                node.path("Image").asText(),
                status,
                health,
                node.path("Ports").asText("").isEmpty()
                        ? "—" : firstPort(node.path("Ports").asText()),
                node.path("Status").asText("—"),
                "—",  // stats require a second call; filled lazily by the UI later
                "—");
    }

    private static String firstPort(String ports) {
        // e.g. "0.0.0.0:5432->5432/tcp, ..." → "5432"
        int arrow = ports.indexOf("->");
        if (arrow > 0) {
            String before = ports.substring(0, arrow);
            int colon = before.lastIndexOf(':');
            return colon >= 0 ? before.substring(colon + 1) : before;
        }
        return ports.split("/")[0];
    }

    /** Starts a container by name or id. */
    public String start(String idOrName) {
        return execIgnoringError("docker", "start", idOrName);
    }

    /** Stops a container by name or id. */
    public String stop(String idOrName) {
        return execIgnoringError("docker", "stop", idOrName);
    }

    /** Restarts a container by name or id. */
    public String restart(String idOrName) {
        return execIgnoringError("docker", "restart", idOrName);
    }

    /**
     * Tails recent logs of a container (no follow — the SSE endpoint streams
     * via repeated polling, which survives gateway hops better than a raw
     * {@code --follow} pipe).
     *
     * @param idOrName container name or id
     * @param tail     number of recent lines
     * @return log lines, newest last
     */
    public List<String> logs(String idOrName, int tail) {
        try {
            String raw = exec("docker", "logs", "--tail", String.valueOf(tail), idOrName);
            if (raw.isBlank()) {
                return List.of();
            }
            return List.of(raw.stripTrailing().split("\n"));
        } catch (Exception e) {
            return List.of("ERROR: " + e.getMessage());
        }
    }

    private String exec(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(System.getenv());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("docker command timed out");
        }
        if (process.exitValue() != 0) {
            String err = new String(process.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException(err.isBlank() ? "exit " + process.exitValue() : err.strip());
        }
        return output;
    }

    private String execIgnoringError(String... command) {
        try {
            String out = exec(command);
            return out.isBlank() ? "ok" : out.strip();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String humanUptime(Instant startedAt) {
        Duration d = Duration.between(startedAt, Instant.now());
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
