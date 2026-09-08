package com.nora.automation.controller;

import com.nora.automation.service.AutomationService;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Automation endpoints per the initiation doc REST contract:
 * rule CRUD, immediate run, execution history.
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

    /** Lists rules, newest first. */
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

    /** Toggles enabled/paused. */
    @PostMapping("/{id}/toggle")
    public ApiResponse<AutomationService.RuleView> toggle(@PathVariable long id) {
        return ApiResponse.ok(service.toggle(id));
    }

    /** Deletes a rule. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!service.delete(id)) {
            throw new com.nora.common.exception.BusinessException(404, "rule not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** Runs a rule immediately. */
    @PostMapping("/{id}/run")
    public ApiResponse<AutomationService.ExecutionView> run(@PathVariable long id) {
        return ApiResponse.ok(service.runNow(id));
    }

    /** Execution history across rules, newest first. */
    @GetMapping("/executions")
    public ApiResponse<List<AutomationService.ExecutionView>> executions(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int bounded = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(service.listExecutions(bounded));
    }

    /** POST /api/automations body. */
    /** actionType 缺省 = "sql";"agent" 时用 prompt。 */
    public record CreateRequest(String name, String triggerType, String actionType, String sql, String prompt) {
    }
}
