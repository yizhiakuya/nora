package com.nora.agent.controller;

import com.nora.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 前端日志接收端点:浏览器全局错误/unhandledrejection/关键动作日志上报到此,
 * 以 JSON 事件行写入主日志(traceId 由 TraceIdFilter 从请求头读取/生成并落 MDC,
 * 前端上报体自带 traceId 时与该请求自身 trace 一致,可交叉检索)。
 * 限流由前端负责(见 errorReporter 环形缓冲);后端只做体积上限防御。
 */
@RestController
@RequestMapping("/api/log")
public class FrontendLogController {

    private static final Logger log = LoggerFactory.getLogger("com.nora.frontend");

    private static final int MAX_PAYLOAD_CHARS = 8000;

    /** POST /api/log/frontend — body: {level, event, message, stack, url, traceId, sessionId, extra} */
    @PostMapping("/frontend")
    public ApiResponse<Void> frontend(@RequestBody Map<String, Object> payload) {
        String level = String.valueOf(payload.getOrDefault("level", "error"));
        String event = abbreviate(String.valueOf(payload.getOrDefault("event", "unknown")), 60);
        String message = abbreviate(String.valueOf(payload.getOrDefault("message", "")), 500);
        String stack = abbreviate(String.valueOf(payload.getOrDefault("stack", "")), 4000);
        String url = abbreviate(String.valueOf(payload.getOrDefault("url", "")), 200);
        String clientTrace = abbreviate(String.valueOf(payload.getOrDefault("traceId", "")), 64);
        String clientSession = abbreviate(String.valueOf(payload.getOrDefault("sessionId", "")), 64);
        Object extra = payload.get("extra");

        switch (level) {
            case "warn" -> log.warn("frontend[{}] url={} clientTrace={} clientSession={} msg={} stack={} extra={}",
                    event, url, clientTrace, clientSession, message, stack, extra);
            case "info" -> log.info("frontend[{}] url={} clientTrace={} clientSession={} msg={} extra={}",
                    event, url, clientTrace, clientSession, message, extra);
            default -> log.error("frontend[{}] url={} clientTrace={} clientSession={} msg={} stack={} extra={}",
                    event, url, clientTrace, clientSession, message, stack, extra);
        }
        return ApiResponse.ok();
    }

    private static String abbreviate(String s, int max) {
        if (s == null || "null".equals(s)) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
    }
}
