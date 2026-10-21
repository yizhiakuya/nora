package com.nora.agent.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.dto.ViewerFile;
import com.nora.agent.service.ViewerService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

@RestController
@RequestMapping("/api/viewer")
public class ViewerController {
    private final ViewerService viewer;

    public ViewerController(ViewerService viewer) { this.viewer = viewer; }

    @PostMapping("/resolve")
    public ApiResponse<ViewerService.ResolvedFiles> resolve(@RequestBody ResolveRequest request) {
        try {
            return ApiResponse.ok(viewer.resolve(request == null ? null : request.targets()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    @GetMapping("/text")
    public ApiResponse<ViewerService.TextContent> text(@RequestParam String target) {
        try {
            return ApiResponse.ok(viewer.text(target));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(404, "文件暂时无法读取，请刷新后重试");
        }
    }

    /** 旧媒体画廊的 HTTP 地址按既有缓存规则读取，之后只返回缓存键。 */
    @PostMapping("/media")
    public ApiResponse<ViewerFile> media(@RequestBody MediaRequest request) {
        try {
            return ApiResponse.ok(viewer.resolveMedia(request == null ? null : request.url()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(404, "媒体暂时无法读取，请稍后重试");
        }
    }

    public record ResolveRequest(List<String> targets) { }
    public record MediaRequest(String url) { }
}
