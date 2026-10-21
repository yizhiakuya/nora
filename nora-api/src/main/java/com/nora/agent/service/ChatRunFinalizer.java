package com.nora.agent.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.ContextCheckpointDto;
import com.nora.agent.dto.TaskContext;

@Service
public class ChatRunFinalizer {
    private static final Logger log = LoggerFactory.getLogger(ChatRunFinalizer.class);
    private final ChatStoreService store;
    private final ObjectMapper mapper;
    private final Path directory;
    // shortcut: 日志属于单 Agent 实例；多实例部署时需要按实例分配提交归属。
    private final ConcurrentMap<String, FinalResult> pending = new ConcurrentHashMap<>();

    public ChatRunFinalizer(ChatStoreService store, ObjectMapper mapper, AgentWorkspaceService workspace) throws IOException {
        this.store = store;
        this.mapper = mapper;
        this.directory = workspace.root().resolve(".runtime/run-finalizations");
        Files.createDirectories(directory);
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid finalization journal: " + file);
                var result = mapper.readValue(file.toFile(), FinalResult.class);
                if (!file.equals(path(result)) || pending.putIfAbsent(result.sessionId(), result) != null) {
                    throw new IOException("Conflicting finalization journal: " + file);
                }
            }
        }
    }

    public void complete(FinalResult result) {
        if (pending.putIfAbsent(result.sessionId(), result) != null) {
            throw new IllegalStateException("Session already has a pending final result: " + result.sessionId());
        }
        reconcileSession(result.sessionId());
    }

    public void reconcileSession(String sessionId) {
        // 执行取消不能中断终态提交；保存后恢复中断信号。
        boolean interrupted = Thread.interrupted();
        try {
            pending.computeIfPresent(sessionId, (id, result) -> {
            try {
                Path file = path(result);
                if (!Files.exists(file)) writeJournal(file, result);
                store.completeRun(result.runId(), result.sessionId(), result.content(), result.steps(), result.sources(),
                        result.durationMs(), result.promptTokens(), result.contextWindow(), result.taskContext(),
                        result.status(), result.stopReason(), result.errorMessage(), result.hadToolErrors(),
                        result.checkpoint(), result.compaction());
                Files.delete(file);
                return null;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot persist final result for run " + result.runId(), e);
            }
            });
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void retryPending() {
        for (String sessionId : pending.keySet()) {
            try { reconcileSession(sessionId); }
            catch (Exception e) { log.debug("final result awaiting persistence for {}: {}", sessionId, e.getClass().getSimpleName()); }
        }
    }

    public boolean hasPending(String sessionId) { return pending.containsKey(sessionId); }

    public Set<String> pendingRunIds() {
        return pending.values().stream().map(FinalResult::runId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Path path(FinalResult result) { return directory.resolve(result.runId() + ".json"); }

    private void writeJournal(Path file, FinalResult result) throws IOException {
        Path temporary = Files.createTempFile(directory, result.runId() + "-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(mapper.writeValueAsBytes(result));
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record FinalResult(String runId, String sessionId, String content, List<ChatStepDto> steps,
                              List<CitationDto> sources, Long durationMs, Integer promptTokens, Long contextWindow,
                              TaskContext taskContext, String status, String stopReason, String errorMessage,
                              boolean hadToolErrors, ContextCheckpointDto checkpoint, boolean compaction) {
        public FinalResult {
            if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,128}")
                    || sessionId == null || sessionId.isBlank() || content == null
                    || status == null || !Set.of("completed", "cancelled", "failed", "partial").contains(status)) {
                throw new IllegalArgumentException("Invalid final result");
            }
            steps = List.copyOf(steps);
            sources = List.copyOf(sources);
        }
    }
}
