package com.nora.agent.controller;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.nora.agent.dto.CommandExecutionDto;
import com.nora.agent.service.TerminalService;
import com.nora.common.response.ApiResponse;

/** UI 仅观察/停止已批准的执行；启动仍走 Agent 工具的审批链。 */
@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/commands")
public class CommandExecutionController {
    private final TerminalService terminal;
    public CommandExecutionController(TerminalService terminal) { this.terminal = terminal; }
    @GetMapping
    public ApiResponse<List<CommandExecutionDto>> list(@PathVariable String sessionId) {
        return ApiResponse.ok(terminal.list(sessionId));
    }
    @GetMapping("/{id}")
    public ApiResponse<CommandExecutionDto> get(@PathVariable String sessionId, @PathVariable String id) {
        return ApiResponse.ok(terminal.get(sessionId, id));
    }
    @PostMapping("/{id}/stop")
    public ApiResponse<CommandExecutionDto> stop(@PathVariable String sessionId, @PathVariable String id) {
        return ApiResponse.ok(terminal.stop(sessionId, id));
    }
    @DeleteMapping
    public ApiResponse<Boolean> clear(@PathVariable String sessionId) {
        terminal.clearFinished(sessionId);
        return ApiResponse.ok(true);
    }
}
