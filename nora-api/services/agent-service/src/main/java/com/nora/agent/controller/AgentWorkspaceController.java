package com.nora.agent.controller;

import com.nora.agent.service.AgentWorkspaceService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 工作区管理 API({@code /api/workspace}):设置中心「工作区」标签页。
 * 与 agent 的 manage_workspace 工具共用同一服务层与路径安全校验。
 */
@RestController
@RequestMapping("/api/workspace")
public class AgentWorkspaceController {

    private final AgentWorkspaceService workspaceService;

    public AgentWorkspaceController(AgentWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /** 工作区概览(路径、文件数、字节数)。 */
    @GetMapping
    public ApiResponse<AgentWorkspaceService.WorkspaceStats> stats() {
        return ApiResponse.ok(workspaceService.stats());
    }

    /** 目录列举(默认根目录)。 */
    @GetMapping("/files")
    public ApiResponse<List<AgentWorkspaceService.FileEntry>> list(
            @RequestParam(required = false) String dir) {
        try {
            return ApiResponse.ok(workspaceService.list(dir));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    /** 读文件内容。 */
    @GetMapping("/file")
    public ApiResponse<FileContent> read(@RequestParam String path) {
        try {
            return ApiResponse.ok(new FileContent(path, workspaceService.read(path)));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(404, e.getMessage());
        }
    }

    /** 写文件(覆盖);前端编辑器保存用。 */
    @PutMapping("/file")
    public ApiResponse<FileContent> write(@RequestBody WriteRequest request) {
        try {
            workspaceService.write(request.path(), request.content());
            return ApiResponse.ok(new FileContent(request.path(), workspaceService.read(request.path())));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    /** 删除文件。 */
    @DeleteMapping("/file")
    public ApiResponse<Void> delete(@RequestParam String path) {
        try {
            workspaceService.delete(path);
            return ApiResponse.ok();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    public record FileContent(String path, String content) {
    }

    public record WriteRequest(String path, String content) {
    }
}
