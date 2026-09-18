package com.nora.agent.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLM 上游流式客户端(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):
 * 两套协议(openai chat.completions / responses)的 SSE 解析、请求体构建与错误友好化。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class UpstreamLlmClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamLlmClient.class);
    /** 上游 LLM SSE 事件时间线专用 logger(独立文件 agent-service-sse.log,见 logback) */
    private static final Logger sseLog = LoggerFactory.getLogger("com.nora.agent.sse");

    private final ObjectMapper objectMapper;
    private final ModelCapabilityRegistry capabilityRegistry;

    UpstreamLlmClient(ObjectMapper objectMapper, ModelCapabilityRegistry capabilityRegistry) {
        this.objectMapper = objectMapper;
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 执行请求并实时转发上游 SSE 块。用原始字节读取 + 增量 UTF-8 解码
     * (中继可能把多字节字符拆到多个块——RestClient 的字符串转换器还会把
     * text/event-stream 弄成 ISO-8859-1,2026-09-05 浏览器实测)。
     * 协议分发:openai → POST /chat/completions(stream),
     * responses → POST /responses(stream)并做事件名映射。
     */
    StreamTurnResult streamUpstream(ResolvedLlm llm, ObjectNode body, TokenSink sink) {
        logUpstreamRequest(llm, body);
        if ("responses".equalsIgnoreCase(llm.protocol())) {
            return streamUpstreamResponses(llm, body, sink);
        }
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // tool_calls 累积:index → {id, name, args-builder}(碎片到达顺序不定)
        Map<Integer, String> callIds = new HashMap<>();
        Map<Integer, String> callNames = new HashMap<>();
        Map<Integer, StringBuilder> callArgs = new HashMap<>();
        List<JsonNode> orderedCalls = new ArrayList<>();
        ChatOrchestrationService.TokenUsage usage = null;

        try {
            java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder
                    .addressFor(llm.baseUrl());
            if (proxyAddr != null) {
                clientBuilder.proxy(java.net.ProxySelector.of(proxyAddr));
            }
            java.net.http.HttpClient client = clientBuilder.build();
            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(stripTrailingSlash(llm.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .header("Accept", MediaType.ALL_VALUE);
            applyExtraHeaders(requestBuilder, llm);
            java.net.http.HttpRequest request = requestBuilder
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            sseLog.info("upstream POST {} -> HTTP {} (model={})",
                    stripTrailingSlash(llm.baseUrl()), response.statusCode(), llm.model());
            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("LLM upstream {} → HTTP {}: body={}", llm.baseUrl(), response.statusCode(), err);
                sseLog.warn("upstream ERROR body={}", abbreviateForSse(err, 400));
                return new StreamTurnResult(true, "上游 " + response.statusCode() + ": " + friendlyUpstreamError(err),
                        "", "", null, List.of(), null);
            }
            boolean done = false;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) { done = true; break; }
                    JsonNode chunkRoot;
                    try {
                        chunkRoot = objectMapper.readTree(payload);
                    } catch (Exception parseError) {
                        continue; // keep-alive comments / partial lines
                    }
                    // 流内错误块:部分中转以 HTTP 200 开流,再把上游 400 塞进 data 里
                    // (形如 data: {"error": {"message": "{...invalid_reasoning_effort...}"}})。
                    // 此前该块没有 choices,被静默忽略 → 最终只报 "empty stream",
                    // 真正的错误文案(以及依赖它的降级判定)全部丢失。
                    // 判定收紧:仅 textual 非空 或 object 非空才算错误——{"error":false}/
                    // {"error":0}/{"error":{}} 这类假值块不能打断正常流。
                    JsonNode errNode = chunkRoot.path("error");
                    if ((errNode.isTextual() && !errNode.asText().isBlank())
                            || (errNode.isObject() && !errNode.isEmpty())) {
                        // 取原始 message 文本(可能仍是转义 JSON),交给 friendlyUpstreamError 统一解析
                        String errText = errNode.isTextual() ? errNode.asText()
                                : errNode.path("message").asText("");
                        if (errText.isBlank()) errText = errNode.toString();
                        log.warn("LLM upstream in-stream error: {}", Texts.abbreviate(errText, 300));
                        sseLog.warn("upstream in-stream ERROR body={}", abbreviateForSse(errText, 400));
                        return new StreamTurnResult(true, "上游 " + friendlyUpstreamError(errText),
                                content.toString(), reasoning.toString(), null, List.of(), usage);
                    }
                    // usage 搭在最后一个 chunk 上(choices 为空数组;中继实测)
                    JsonNode usageNode = chunkRoot.path("usage");
                    if (usageNode.isObject() && !usageNode.isEmpty()) {
                        usage = new ChatOrchestrationService.TokenUsage(
                                usageNode.path("prompt_tokens").isInt() ? usageNode.path("prompt_tokens").asInt() : null,
                                usageNode.path("completion_tokens").isInt() ? usageNode.path("completion_tokens").asInt() : null,
                                usageNode.path("total_tokens").isInt() ? usageNode.path("total_tokens").asInt() : null);
                    }
                    JsonNode delta = chunkRoot.path("choices").path(0).path("delta");
                    JsonNode rc = delta.path("reasoning_content");
                    if (!rc.isTextual()) rc = delta.path("reasoning");
                    if (rc.isTextual() && !rc.asText().isEmpty()) {
                        reasoning.append(rc.asText());
                        sink.accept(null, rc.asText());
                    }
                    JsonNode ct = delta.path("content");
                    if (ct.isTextual() && !ct.asText().isEmpty()) {
                        content.append(ct.asText());
                        sink.accept(ct.asText(), null);
                    }
                    JsonNode tcs = delta.path("tool_calls");
                    if (tcs.isArray()) {
                        for (JsonNode tc : tcs) {
                            int idx = tc.path("index").asInt(callIds.size());
                            callIds.computeIfAbsent(idx, k -> tc.path("id").asText(""));
                            JsonNode fn = tc.path("function");
                            if (fn.has("name") && fn.path("name").isTextual() && !fn.path("name").asText().isEmpty()) {
                                // 工具名只完整出现一次;若中转把 name 分片/重复发送(含 JSON null),
                                // 直接覆盖而非拼串,避免拼成 "execute_sqlnullnull…"
                                callNames.put(idx, fn.path("name").asText());
                            }
                            JsonNode args = fn.path("arguments");
                            if (args.isTextual() && !args.asText().isEmpty()) {
                                callArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(args.asText());
                            }
                        }
                    }
                }
            }
            if (!done && content.isEmpty() && reasoning.isEmpty() && callArgs.isEmpty()) {
                return new StreamTurnResult(true, "empty stream", "", "", null, List.of(), usage);
            }
            // 按 index 序用累积的 id/name/args 重建 tool_calls
            for (Integer idx : new java.util.TreeSet<>(callArgs.isEmpty() ? callIds.keySet() : unionKeys(callIds, callArgs))) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("id", callIds.getOrDefault(idx, "call_" + idx));
                ObjectNode fn = call.putObject("function");
                fn.put("name", callNames.getOrDefault(idx, ""));
                fn.put("arguments", callArgs.containsKey(idx) ? callArgs.get(idx).toString() : "{}");
                orderedCalls.add(call);
            }
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", content.toString());
            if (!orderedCalls.isEmpty()) {
                ArrayNode arr = assistant.putArray("tool_calls");
                for (JsonNode call : orderedCalls) {
                    // 部分上游(实测 DeepSeek-V4-Flash)严格要求每个 tool_call 带
                    // "type":"function",缺失即 400(报错被中转粒化为 "Upstream error: 400",
                    // 极难定位)。这里统一补齐,对宽松上游无副作用。
                    if (call.isObject() && !call.has("type")) {
                        ((ObjectNode) call).put("type", "function");
                    }
                    arr.add(call);
                }
            }
            sseLog.info("upstream round done: contentChars={} reasoningChars={} toolCalls={} usage={}",
                    content.length(), reasoning.length(), orderedCalls.size(),
                    usage == null ? "none" : "in=" + usage.inputTokens() + " out=" + usage.outputTokens());
            return new StreamTurnResult(false, null, content.toString(), reasoning.toString(),
                    assistant, orderedCalls, usage);
        } catch (Exception e) {
            // 用户取消时 HttpClient 阻塞读抛 IOException(InterruptedException):
            // 恢复中断标志让编排层据此短路(不做空响应重试/最终回答兜底)
            if (Texts.isInterruption(e)) {
                Thread.currentThread().interrupt();
                sseLog.warn("upstream STREAM FAILED: {} (turn cancelled)", e.toString());
            } else {
                sseLog.warn("upstream STREAM FAILED: {}", e.toString());
            }
            log.error("LLM upstream stream failed (protocol={}): {}", llm.protocol(), e.toString(), e);
            return new StreamTurnResult(true, e.toString(), content.toString(), reasoning.toString(),
                    null, List.of(), usage);
        }
    }

    /**
     * {@link #streamUpstream} 的 Responses API(POST {base}/responses,SSE)变体。
     * 请求体转换(chat.completions 形态 → responses 形态):
     * - messages → input[],type: message(role/content)
     * - assistant 的 tool_calls → output_item 消息,type: function_call + call_id
     * - 工具结果 → input 项 type: function_call_output
     * - tools[] 拍平 function → {type:"function", name, description, parameters}
     * - reasoning_effort 透传;stream_options 丢弃
     * 事件映射(SSE `event:` 行):
     * - response.output_text.delta            → content token
     * - response.reasoning_summary_text.delta → reasoning token
     * - response.output_item.added (function_call) / response.function_call_arguments.delta → 工具累积
     * - response.completed → 从 response.usage 取用量
     */
    private StreamTurnResult streamUpstreamResponses(ResolvedLlm llm, ObjectNode chatBody, TokenSink sink) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // 按 item_id 键控的 function_call 累积
        Map<String, String> callIds = new LinkedHashMap<>();
        Map<String, String> callNames = new LinkedHashMap<>();
        Map<String, StringBuilder> callArgs = new LinkedHashMap<>();
        ChatOrchestrationService.TokenUsage usage = null;

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", chatBody.path("model").asText());
            body.put("stream", true);
            if (chatBody.hasNonNull("reasoning_effort")) {
                body.putObject("reasoning").put("effort", chatBody.get("reasoning_effort").asText());
            }
            // 摘要推理:部分模型(如 muse 系列)原始推理内容是 encrypted_content,不请求
            // summary 就没有任何可见的思考文本——上游支持时必须带 summary=auto,
            // 否则前端"思考过程"时间线对这类模型永远是空的。
            if (!body.has("reasoning")) {
                body.putObject("reasoning").put("summary", "auto");
            } else {
                JsonNode reasoningNode = body.path("reasoning");
                if (reasoningNode.isObject()) {
                    ((ObjectNode) reasoningNode).put("summary", "auto");
                }
            }
            // tools:拍平 {type:function, function:{...}} → {type:function, name, ...}
            ArrayNode tools = body.putArray("tools");
            for (JsonNode t : chatBody.path("tools")) {
                if (!"function".equals(t.path("type").asText())) continue;
                ObjectNode flat = tools.addObject();
                flat.put("type", "function");
                flat.put("name", t.path("function").path("name").asText());
                flat.put("description", t.path("function").path("description").asText(""));
                flat.set("parameters", t.path("function").path("parameters"));
            }
            // messages → input[];工具结果 → function_call_output 项。
            // Responses API 没有 system 角色:系统提示词搭在 instructions 上。
            StringBuilder instructions = new StringBuilder();
            ArrayNode input = body.putArray("input");
            for (WireMessage wm : wireMessagesOf(chatBody)) {
                ObjectNode m = wm.node();
                String role = m.path("role").asText("");
                if ("tool".equals(role)) {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call_output");
                    item.put("call_id", m.path("tool_call_id").asText(""));
                    JsonNode toolContent = m.path("content");
                    if (toolContent.isArray()) {
                        // 多模态工具结果:转为 output 数组(input_text + input_image)。
                        // 实测验证:responses 协议的 function_call_output.output 支持该形式。
                        ArrayNode outParts = item.putArray("output");
                        for (JsonNode part : toolContent) {
                            String pType = part.path("type").asText("");
                            if ("text".equals(pType)) {
                                outParts.addObject().put("type", "input_text").put("text", part.path("text").asText(""));
                            } else if ("image_url".equals(pType)) {
                                outParts.addObject().put("type", "input_image")
                                        .put("image_url", part.path("image_url").path("url").asText(""));
                            }
                        }
                    } else {
                        item.put("output", toolContent.asText(""));
                    }
                    continue;
                }
                if ("system".equals(role)) {
                    if (instructions.length() > 0) instructions.append("\n\n");
                    instructions.append(m.path("content").asText(""));
                    continue;
                }
                ObjectNode msgItem = input.addObject();
                msgItem.put("type", "message");
                msgItem.put("role", "assistant".equals(role) ? "assistant" : "user");
                ArrayNode parts = msgItem.putArray("content");
                ObjectNode part = parts.addObject();
                part.put("type", "assistant".equals(role) ? "output_text" : "input_text");
                part.put("text", m.path("content").asText(""));
                // 发起过 tool_calls 的 assistant 轮:在消息后发出 function_call 项
                JsonNode calls = m.path("tool_calls");
                if (calls.isArray()) {
                    for (JsonNode c : calls) {
                        ObjectNode callItem = input.addObject();
                        callItem.put("type", "function_call");
                        callItem.put("call_id", c.path("id").asText());
                        callItem.put("name", c.path("function").path("name").asText());
                        callItem.put("arguments", c.path("function").path("arguments").asText("{}"));
                    }
                }
            }
            if (instructions.length() > 0) {
                body.put("instructions", instructions.toString());
            }

            java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder
                    .addressFor(llm.baseUrl());
            if (proxyAddr != null) {
                clientBuilder.proxy(java.net.ProxySelector.of(proxyAddr));
            }
            java.net.http.HttpClient client = clientBuilder.build();
            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(stripTrailingSlash(llm.baseUrl()) + "/responses"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .header("Accept", MediaType.ALL_VALUE);
            applyExtraHeaders(requestBuilder, llm);
            java.net.http.HttpRequest request = requestBuilder
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            sseLog.info("upstream POST {} -> HTTP {} (model={})",
                    stripTrailingSlash(llm.baseUrl()), response.statusCode(), llm.model());
            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("LLM upstream {} → HTTP {}: body={}", llm.baseUrl(), response.statusCode(), err);
                sseLog.warn("upstream ERROR body={}", abbreviateForSse(err, 400));
                return new StreamTurnResult(true, "上游 " + response.statusCode() + ": " + friendlyUpstreamError(err),
                        "", "", null, List.of(), null);
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                String eventName = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) { eventName = line.substring(6).trim(); continue; }
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) break;
                    JsonNode root;
                    try { root = objectMapper.readTree(payload); } catch (Exception e) { continue; }
                    String type = root.path("type").asText(eventName);
                    // 流内错误:responses 协议有标准失败事件(response.failed / error),
                    // 部分中转还会把上游错误塞进 data 的 error 字段。与 chat 路径同款:
                    // 取出真实错误文案返回 failed——否则只报 "empty stream",依赖错误
                    // 文案的降级判定(invalid_reasoning_effort)永远不会触发。
                    // 注:事件名是字面量,勿把 Java 访问器风格 x() 误替换进来
                    // (2026-09-19 审查修复:e42590e 拆分时被误改成 response.failed()/
                    // reasoning()_summary_text.delta,导致推理内容全部被吞、失败事件漏检)
                    if ("error".equals(type) || "response.failed".equals(type)
                            || (root.path("error").isObject() && !root.path("error").isEmpty())
                            || (root.path("error").isTextual() && !root.path("error").asText().isBlank())) {
                        JsonNode errNode = root.path("error");
                        if (errNode.isMissingNode() || errNode.isNull()) {
                            errNode = root.path("response").path("error");
                        }
                        String errText = errNode.isTextual() ? errNode.asText()
                                : errNode.path("message").asText("");
                        if (errText.isBlank()) errText = errNode.isMissingNode() ? root.toString() : errNode.toString();
                        log.warn("LLM upstream in-stream error (responses): {}", Texts.abbreviate(errText, 300));
                        sseLog.warn("upstream in-stream ERROR body={}", abbreviateForSse(errText, 400));
                        return new StreamTurnResult(true, "上游 " + friendlyUpstreamError(errText),
                                content.toString(), reasoning.toString(), null, List.of(), usage);
                    }
                    if ("response.output_text.delta".equals(type)) {
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) { content.append(delta); sink.accept(delta, null); }
                    } else if ("response.reasoning_summary_text.delta".equals(type)
                            || "response.reasoning_text.delta".equals(type)) {
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) { reasoning.append(delta); sink.accept(null, delta); }
                    } else if ("response.output_item.added".equals(type)) {
                        JsonNode item = root.path("item");
                        if ("function_call".equals(item.path("type").asText())) {
                            String itemId = item.path("id").asText();
                            callIds.putIfAbsent(itemId, item.path("call_id").asText(itemId));
                            callNames.putIfAbsent(itemId, item.path("name").asText(""));
                            callArgs.computeIfAbsent(itemId, k -> new StringBuilder())
                                    .append(item.path("arguments").asText(""));
                        }
                    } else if ("response.function_call_arguments.delta".equals(type)) {
                        String itemId = root.path("item_id").asText("");
                        String delta = root.path("delta").asText("");
                        if (!itemId.isEmpty() && !delta.isEmpty()) {
                            callArgs.computeIfAbsent(itemId, k -> new StringBuilder()).append(delta);
                        }
                    } else if ("response.completed".equals(type)) {
                        JsonNode u = root.path("response").path("usage");
                        if (u.isObject() && !u.isEmpty()) {
                            usage = new ChatOrchestrationService.TokenUsage(
                                    u.path("input_tokens").isInt() ? u.path("input_tokens").asInt() : null,
                                    u.path("output_tokens").isInt() ? u.path("output_tokens").asInt() : null,
                                    u.path("total_tokens").isInt() ? u.path("total_tokens").asInt() : null);
                        }
                    }
                }
            }
            if (content.isEmpty() && reasoning.isEmpty() && callArgs.isEmpty()) {
                return new StreamTurnResult(true, "empty stream", "", "", null, List.of(), usage);
            }
            // 重建 assistant 消息:content + tool_calls(chat.completions 形态,
            // 让 ReAct 循环/持久化层保持协议无关)
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", content.toString());
            List<JsonNode> orderedCalls = new ArrayList<>();
            for (String itemId : callArgs.keySet()) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("id", callIds.getOrDefault(itemId, itemId));
                ObjectNode fn = call.putObject("function");
                fn.put("name", callNames.getOrDefault(itemId, ""));
                fn.put("arguments", callArgs.get(itemId).toString());
                orderedCalls.add(call);
            }
            if (!orderedCalls.isEmpty()) {
                ArrayNode arr = assistant.putArray("tool_calls");
                for (JsonNode call : orderedCalls) {
                    // 同 openai 路径:补齐 "type":"function"(部分上游严格校验)
                    if (call.isObject() && !call.has("type")) {
                        ((ObjectNode) call).put("type", "function");
                    }
                    arr.add(call);
                }
            }
            // reasoningChars 必须记录:此前 responses 分支漏了这一项,排查
            // 「思考等级失效」时只能靠直连上游重放才能判断上游到底有没有产推理,
            // 日志里看不出(openai 分支一直是全的)。
            sseLog.info("upstream round done (responses): contentChars={} reasoningChars={} toolCalls={} usage={}",
                    content.length(), reasoning.length(), orderedCalls.size(),
                    usage == null ? "none" : "in=" + usage.inputTokens() + " out=" + usage.outputTokens());
            return new StreamTurnResult(false, null, content.toString(), reasoning.toString(),
                    assistant, orderedCalls, usage);
        } catch (Exception e) {
            if (Texts.isInterruption(e)) {
                Thread.currentThread().interrupt();
                sseLog.warn("upstream STREAM FAILED: {} (turn cancelled)", e.toString());
            } else {
                sseLog.warn("upstream STREAM FAILED: {}", e.toString());
            }
            log.error("LLM upstream stream failed (protocol={}): {}", llm.protocol(), e.toString(), e);
            return new StreamTurnResult(true, e.toString(), content.toString(), reasoning.toString(),
                    null, List.of(), usage);
        }
    }

    private void logUpstreamRequest(ResolvedLlm llm, ObjectNode body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            log.debug("LLM upstream request: protocol={} model={} level={} url={}/... body={}",
                    llm.protocol(), llm.model(), llm.effectiveReasoningLevel(),
                    stripTrailingSlash(llm.baseUrl()), objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.debug("LLM upstream request: protocol={} model={} url={} (body serialize failed: {})",
                    llm.protocol(), llm.model(), llm.baseUrl(), e.toString());
        }
    }

    private java.net.http.HttpRequest.Builder applyExtraHeaders(java.net.http.HttpRequest.Builder builder, ResolvedLlm llm) {
        String[] extra = providerExtraHeaders(llm);
        if (extra.length > 0) {
            builder.headers(extra);
        }
        return builder;
    }

    private String[] providerExtraHeaders(ResolvedLlm llm) {
        if (llm.baseUrl() != null && llm.baseUrl().contains("opencode.ai")) {
            String key = llm.apiKey() == null ? "" : llm.apiKey();
            String sessionId = "nora-" + Integer.toHexString(key.hashCode());
            return new String[]{"X-Session-ID", sessionId};
        }
        return new String[0];
    }

    private static String stripTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    ObjectNode baseBody(boolean stream, ResolvedLlm llm) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llm.model());
        body.put("stream", stream);
        applyReasoningRequest(body, llm);
        return body;
    }

    /**
     * OpenAI 兼容的思考开关;原生支持推理的模型无需覆盖。
     * 生效等级(effectiveReasoningLevel)来自设置页 per-model 配置或对话框请求:
     * - 用户显式选的档位一律原样透传(含 none=关闭);家族名单只决定“未指定时的默认值”:
     *   gpt-5/o 默认 medium,claude thinking 与带档位后缀的模型不注入
     *   (由上游按模型自身默认决定,claude 不带该字段就没有推理内容)。
     *   上游拒绝该取值时自动剥离并重试,重试成功才记住该档位(见 effortRejectedModels)。
     * - qwen/glm:开关式字段,none 关、其余开。
     */
    void applyReasoningRequest(ObjectNode body, ResolvedLlm llm) {
        // 协议白名单只排除 ollama:其原生 /api/chat 不认 reasoning_effort。
        // openai / responses / anthropic 都注入——
        //   responses 走 reasoning:{effort} 映射(见 streamUpstreamResponses);
        //   anthropic 在本实现里同样以 chat.completions 形状发出(见 streamUpstream 协议分发),
        //   档位也用 reasoning_effort。
        // 此前按协议名直接 return,anthropic 选了档位也发不出去(实测 2026-09-15:
        // protocol=anthropic 的请求体里没有 reasoning_effort,上游 reasoningChars=0;
        // 同一 key 手工加 reasoning_effort=xhigh 立刻出 261 字符推理)。
        // 不认该字段的上游由既有的降级重试兜底(见 effortRejectedModels)。
        if ("ollama".equalsIgnoreCase(llm.protocol())) return;
        String model = llm.model() == null ? "" : llm.model().toLowerCase();
        String requested = llm.effectiveReasoningLevel();
        boolean hasRequested = requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested);
        boolean off = hasRequested && "none".equalsIgnoreCase(requested);
        boolean effortFamily = supportsReasoningEffort(model);
        boolean openAiDefaultFamily = model.contains("gpt-5") || model.matches("(?s).*\\bo[1-9].*");
        // 已探测到该模型拒绝**这个档位**:跳过注入,避免每次都先撞 400 再重试。
        // 键含档位——用户换成受支持的等级后仍会正常注入(见 effortKey)。
        // 只跳过 reasoning_effort,qwen/glm 的开关式字段仍照常处理(它们在下面)。
        boolean effortRejected = capabilityRegistry.isEffortRejected(llm);
        // 用户显式选的档位一律透传——家族白名单只管"未指定时的默认值"。
        // 此前把显式档位也锁在 effortFamily 里,导致不在名单的模型(如 deepseek-v4.1-flash)
        // 选了档位却被静默丢弃:responses 分支拿不到 reasoning_effort 就只发 summary:auto,
        // 上游实测该形态不产出任何 reasoning(0 事件),思考等级等于失效。
        // 实测(2026-09-15, 中转站 192.168.0.109:28765):
        //   chat  + reasoning_effort=xhigh  → 4397 分片
        //   chat  + reasoning_effort=minimal→ 5878 分片
        //   responses + reasoning{effort:xhigh,summary:auto} → 7414 分片
        //   responses + reasoning{summary:auto}(无 effort)    → 0 分片
        // 不认该字段的模型家族由上游忽略即可,不会报错(glm 同发 reasoning_effort + thinking 实测 200)。
        if (!effortRejected && (effortFamily || hasRequested)) {
            if (off) {
                // 「none = 关闭思考」直传 none,不再降级成 minimal。
                // 旧实现固定写 minimal,但实测该上游的 minimal 仍会思考
                // (deepseek-v4.1-flash: responses+minimal → 2788 reasoning 分片),
                // 导致用户选「none」根本关不掉;none 本身被上游接受(200)。
                body.put("reasoning_effort", "none");
            } else if (hasRequested && isOpenAiEffort(requested)) {
                body.put("reasoning_effort", requested);
            } else if (openAiDefaultFamily) {
                // 历史默认:OpenAI 家族不指定时也要 medium(上游不默认开推理)
                body.put("reasoning_effort", "medium");
            }
            // 其余家族(claude/带后缀)未指定时不注入,交由上游默认
        }
        if (model.contains("qwen")) {
            body.put("enable_thinking", !off);
        }
        if (model.contains("glm")) {
            body.putObject("thinking").put("type", off ? "disabled" : "enabled");
        }
    }

    private static boolean isOpenAiEffort(String level) {
        return switch (level.toLowerCase()) {
            case "none", "minimal", "low", "medium", "high", "xhigh", "max" -> true;
            default -> false;
        };
    }

    private static boolean supportsReasoningEffort(String model) {
        if (model.contains("gpt-5") || model.matches("(?s).*\\bo[1-9].*")) return true;
        if (model.contains("claude")) return true;
        // 中转站为非 OpenAI 模型附加的思考档位后缀:gemini-3.6-flash-high / deepseek-v4-pro-high 等
        return model.matches("(?s).*(high|xhigh|max|minimal|low)$");
    }

    private static String abbreviateForSse(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + " chars)";
    }

    /**
     * 把上游返回的错误体转成一行可读信息:能解析出 {@code error.message} 就取它,
     * 否则退化为 HTTP 状态码短语——避免把整段 {@code {"error":{...}}} JSON 甩给前端/用户。
     */
    private static String friendlyUpstreamError(String body) {
        if (body == null || body.isBlank()) {
            return "无返回信息";
        }
        final String trimmed = body.trim();
        // 部分中转把上游错误体塞进 message 字段的**转义 JSON 字符串**里,形如
        // {"error":{"message":"{\"code\":11150,\"msg\":\"the reasoning effort value
        // is not supported...\"}"}}。朴素的“读到下一个引号”会在第一个 \" 处截断,
        // 只剩 "{\" —— 调用方(如档位降级判定)就看不到真正的错误文案。
        // 这里先把转义引号还原,再按嵌套结构取最内层的可读消息。
        String unescaped = trimmed.replace("\\\"", "\"");
        String inner = deepestMessage(unescaped);
        if (inner != null) {
            return withUpstreamHint(inner);
        }
        // 常见错误体形如 {"error":{"message":"...","type":"..."}}
        final int msgIdx = trimmed.indexOf("\"message\"");
        if (msgIdx >= 0) {
            int start = trimmed.indexOf(':', msgIdx) + 1;
            while (start < trimmed.length() && (trimmed.charAt(start) == ' ' || trimmed.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < trimmed.length() && trimmed.charAt(end) != '"') {
                end++;
            }
            String message = end > start ? trimmed.substring(start, end) : null;
            if (message != null && !message.isBlank()) {
                return withUpstreamHint(message);
            }
        }
        return Texts.abbreviate(trimmed, 160);
    }

    /**
     * 中转站透传的 "Upstream error: N" 无信息量,补一句状态码语义。
     * 提取成共用逻辑:嵌套转义 JSON 的早返回路径与平铺路径都要带上这条提示
     * (此前早返回把该提示挤成死代码,9c346a0 修复的语义丢失)。
     */
    private static String withUpstreamHint(String message) {
        if (message.matches("(?i)upstream (error|unavailable).*")) {
            return message + "（上游模型服务暂不可用,稍后重试）";
        }
        return Texts.abbreviate(message, 160);
    }

    /**
     * 从（已还原转义引号的）错误体里取最内层可读消息。
     * 嵌套形如 {"error":{"message":"{\"code\":N,\"msg\":\"...\",\"extError\":{...}}"}}——
     * 还原后外层 message 的值本身又是一段 JSON。取最内层的 msg/message 字段才有信息量。
     * 取不到时返回 null，由调用方走原来的平铺解析。
     */
    private static String deepestMessage(String unescaped) {
        String best = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(?:msg|message)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(unescaped);
        while (m.find()) {
            String value = m.group(1).trim();
            // 跳过纯 JSON 片段（值以 { 开头说明它自己还是个对象，不是可读文案）
            if (value.isEmpty() || value.startsWith("{") || value.startsWith("[")) continue;
            best = value;
        }
        return best;
    }

    private List<WireMessage> wireMessagesOf(ObjectNode chatBody) {
        List<WireMessage> result = new ArrayList<>();
        for (JsonNode m : chatBody.path("messages")) {
            if (m instanceof ObjectNode o) result.add(new WireMessage(o));
        }
        return result;
    }

    private static java.util.Set<Integer> unionKeys(Map<Integer, String> a, Map<Integer, StringBuilder> b) {
        java.util.Set<Integer> all = new java.util.TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        return all;
    }

    ArrayNode messagesArray(List<WireMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (WireMessage message : messages) {
            array.add(message.node());
        }
        return array;
    }

    interface TokenSink {
        void accept(String content, String reasoning);
    }

}
