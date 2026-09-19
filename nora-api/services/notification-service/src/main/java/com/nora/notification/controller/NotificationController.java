package com.nora.notification.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nora.common.response.ApiResponse;
import com.nora.notification.service.NotificationStore;

/**
 * 通知中心 API(2026-09-19):前端拉取/已读/清空。
 * 事件产生走 Kafka(业务服务 producer),这里只读与管理。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationStore store;

    public NotificationController(NotificationStore store) {
        this.store = store;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /** 最近通知(最新在前)。 */
    @GetMapping
    public ApiResponse<List<NotificationStore.NotificationView>> list(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(store.list(limit == null ? 50 : limit));
    }

    /**
     * 前端上报一条通知(2026-09-19):上传完成/数据源连接等「用户当前动作」
     * 由前端直接产生——统一走服务端存储,跨浏览器一致(此前只在 localStorage)。
     * 后端服务的事件走 Kafka(producer),此端点只服务前端。
     */
    @PostMapping
    public ApiResponse<Long> create(@org.springframework.web.bind.annotation.RequestBody CreateRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new com.nora.common.exception.BusinessException(400, "title is required");
        }
        long id = store.save(request.event(), request.title(), request.detail(), "frontend");
        return ApiResponse.ok(id);
    }

    /** POST /api/notifications 请求体。 */
    public record CreateRequest(String event, String title, String detail) {
    }

    /** 未读数(铃铛角标;轻量轮询用)。 */
    @GetMapping("/unread-count")
    public ApiResponse<Integer> unreadCount() {
        return ApiResponse.ok(store.unreadCount());
    }

    /** 全部标记已读。 */
    @PostMapping("/read-all")
    public ApiResponse<Integer> markAllRead() {
        return ApiResponse.ok(store.markAllRead());
    }

    /** 单条标记已读。 */
    @PostMapping("/{id}/read")
    public ApiResponse<Integer> markRead(@PathVariable long id) {
        return ApiResponse.ok(store.markRead(id));
    }

    /** 清空全部。 */
    @DeleteMapping
    public ApiResponse<Integer> clearAll() {
        return ApiResponse.ok(store.clearAll());
    }
}
