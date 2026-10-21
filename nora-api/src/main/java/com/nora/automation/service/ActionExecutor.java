package com.nora.automation.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatRequest;
import com.nora.agent.service.ChatRunService;
import com.nora.common.exception.BusinessException;
import com.nora.datasource.service.DatasourceServiceImpl;

/** 自动任务提交同一套持久化 Run，直接消费终态，不解析 UI 的 SSE。 */
@Service
public class ActionExecutor {
    private final DatasourceServiceImpl datasource;
    private final ObjectProvider<ChatRunService> runs;
    private final ObjectMapper mapper;

    public ActionExecutor(DatasourceServiceImpl datasource, ObjectProvider<ChatRunService> runs, ObjectMapper mapper) {
        this.datasource = datasource;
        // Agent tools can manage schedules; resolve the runtime only when executing an action.
        this.runs = runs;
        this.mapper = mapper;
    }

    public record ActionResult(String detail, String status) {
        static ActionResult completed(String detail) { return new ActionResult(detail, "completed"); }
        static ActionResult failed(String message) { return new ActionResult("ERROR: " + message, "failed"); }
        static ActionResult unknown(String message) { return new ActionResult("ERROR: " + message, "unknown"); }
    }

    public ActionResult execute(String actionJson, String sessionId, String sessionTitle) {
        com.fasterxml.jackson.databind.JsonNode action;
        try { action = mapper.readTree(actionJson); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return ActionResult.failed("invalid action JSON: " + e.getMessage());
        }
        if (action == null || !action.isObject()) return ActionResult.failed("action must be an object");
        if ("sql".equals(action.path("type").asText())) {
            var id = action.path("connectionId");
            String sql = action.path("sql").asText("");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0) {
                return ActionResult.failed("SQL 任务未绑定有效数据源，请从目标查询控制台重新保存");
            }
            if (sql.isBlank()) return ActionResult.failed("action SQL is empty");
            try { return ActionResult.completed(datasource.execute(id.asLong(), sql).toTsv()); }
            catch (BusinessException e) {
                return e.getCode() < 500 ? ActionResult.failed(e.getMessage()) : unknownSql(e.getMessage());
            } catch (org.springframework.dao.DataAccessException e) { return unknownSql(e.getMessage()); }
        }
        if (!"agent".equals(action.path("type").asText())) return ActionResult.failed("unsupported action type");
        String prompt = action.path("prompt").asText("");
        if (prompt.isBlank()) return ActionResult.failed("action prompt is empty");
        if (sessionId == null || sessionId.isBlank()) return ActionResult.failed("agent action requires a session");
        ChatRunService.Handle handle;
        try {
            handle = runs.getObject().start(sessionId, new ChatRequest(prompt, null, null, "full", null,
                    null, "automation", true, sessionTitle == null ? "定时任务" : sessionTitle));
        } catch (BusinessException | IllegalArgumentException e) { return ActionResult.failed(e.getMessage()); }
        try {
            ChatRunService.Result result = handle.completion().get();
            String detail = result.answer() == null ? "" : result.answer();
            if (result.error() != null) detail += "\nERROR: " + result.error();
            return new ActionResult(detail, result.status());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.unknown("等待已中断，Run " + handle.runId() + " 可能仍在执行，请查看会话");
        } catch (java.util.concurrent.ExecutionException e) {
            return ActionResult.unknown("Run " + handle.runId() + " 终态未确认，请查看会话: " + e.getMessage());
        }
    }

    private static ActionResult unknownSql(String message) {
        return ActionResult.unknown("数据库命令结果未知: " + message + "。请求可能已生效；先查询核对，不能直接重发。");
    }

    public String sqlAction(String sql, Long connectionId) {
        return mapper.createObjectNode().put("type", "sql").put("sql", sql).put("connectionId", connectionId).toString();
    }

    public String agentAction(String prompt) {
        return mapper.createObjectNode().put("type", "agent").put("prompt", prompt).toString();
    }
}
