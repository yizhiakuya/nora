package com.nora.automation.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nora.automation.service.AutomationService;
import com.nora.common.response.ApiResponse;

/**
 * 自动化端点(立项文档 REST 契约):规则 CRUD、立即运行、执行历史。
 */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    private final AutomationService service;

    public AutomationController(AutomationService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /** 列出规则,最新在前。 */
    @GetMapping
    public ApiResponse<List<AutomationService.RuleView>> list() {
        return ApiResponse.ok(service.list());
    }

    /** Creates a rule (SQL 或 agent 动作,由 actionType 区分). */
    @PostMapping
    public ApiResponse<AutomationService.RuleView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.triggerType(),
                request.actionType(), request.sql(), request.prompt()));
    }

    /** 切换启用/暂停。 */
    @PostMapping("/{id}/toggle")
    public ApiResponse<AutomationService.RuleView> toggle(@PathVariable long id) {
        return ApiResponse.ok(service.toggle(id));
    }

    /** 删除规则。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!service.delete(id)) {
            throw new com.nora.common.exception.BusinessException(404, "rule not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** 立即运行规则。 */
    @PostMapping("/{id}/run")
    public ApiResponse<AutomationService.ExecutionView> run(@PathVariable long id) {
        return ApiResponse.ok(service.runNow(id));
    }

    /** 跨规则的执行历史,最新在前。 */
    @GetMapping("/executions")
    public ApiResponse<List<AutomationService.ExecutionView>> executions(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int bounded = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(service.listExecutions(bounded));
    }

    /** POST /api/automations 请求体。 */
    /** actionType 缺省 = "sql";"agent" 时用 prompt。 */
    public record CreateRequest(String name, String triggerType, String actionType, String sql, String prompt) {
    }
}
