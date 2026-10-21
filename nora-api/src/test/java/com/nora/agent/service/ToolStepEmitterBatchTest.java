package com.nora.agent.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

/** 冒烟执行真实终端和文件工具;并发、回填与取消的行为由真实服务 E2E 验证。 */
class ToolStepEmitterBatchTest {
    @TempDir Path workspace;

    @Test
    void executesParallelAndSerialBatches() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentWorkspaceService files = new AgentWorkspaceService(workspace.toString());
        TerminalService terminal = TestServices.terminal(workspace);
        ChatToolExecutor executor = new ChatToolExecutor(mapper, ToolSmokeFixtures.annotated(mapper, ToolSmokeFixtures.builtin(mapper, null, null, null, terminal, null, null, files), new FileWorkspaceTools(null, files, null, null)), null, files, null, null, null);
        TurnCancellation cancellation = new TurnCancellation();
        ToolStepEmitter emitter = new ToolStepEmitter(mapper, null, executor, null, cancellation);
        List<ChatStepDto> steps = new CopyOnWriteArrayList<>();
        var consumer = new ChatOrchestrationService.ChatEventConsumer() {
            @Override public void step(ChatStepDto step) { steps.add(step); }
            @Override public void delta(String token) { }
            @Override public void sources(List<CitationDto> citations) { }
        };
        List<WireMessage> messages = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = windows ? "Start-Sleep -Milliseconds 100; Write-Output 'batch'" : "sleep 0.1; echo batch";
        List<dev.langchain4j.agent.tool.ToolExecutionRequest> calls = List.of(call(mapper, "first", TerminalService.toolName(), mapper.createObjectNode().put("command", command)),
                call(mapper, "second", TerminalService.toolName(), mapper.createObjectNode().put("command", command.replace("batch", "batch-second"))));
        emitter.emitToolCalls(calls, new LoopDetector(), messages, 1, PermissionMode.FULL,
                "batch", false, null, consumer);
        files.write("sample.md", "sample");
        calls = List.of(call(mapper, "read", "manage_workspace", mapper.createObjectNode().put("action", "read").put("path", "sample.md")),
                call(mapper, "write", "manage_workspace", mapper.createObjectNode().put("action", "write").put("path", "sample.md").put("content", "updated")));
        emitter.emitToolCalls(calls, new LoopDetector(), messages, 2, PermissionMode.FULL,
                "batch", false, null, consumer);
        emitter.emitToolCalls(calls.subList(0, 1), new LoopDetector(), messages, 3, PermissionMode.ASK,
                "batch", false, null, consumer);
        files.write("crlf.md", "first\r\nsecond\r\n");
        emitter.emitToolCalls(List.of(call(mapper, "edit", "manage_workspace", mapper.createObjectNode()
                .put("action", "edit").put("path", "crlf.md").put("old_string", "first\nsecond")
                .put("new_string", "updated\nlines"))), new LoopDetector(), messages, 4, PermissionMode.FULL,
                "batch", false, null, consumer);
        cancellation.request("batch");
        try {
            emitter.emitToolCalls(calls, new LoopDetector(), messages, 4, PermissionMode.FULL,
                    "batch", false, null, consumer);
        } catch (java.util.concurrent.CancellationException ignored) { }
    }

    private dev.langchain4j.agent.tool.ToolExecutionRequest call(ObjectMapper mapper, String id, String name, JsonNode args) {
        return dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(id).name(name).arguments(args.toString()).build();
    }
}
