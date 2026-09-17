package com.nora.agent.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 一次流式模型轮次:转发的 content/reasoning + 累积的 tool_calls。 */
record StreamTurnResult(boolean failed, String errorMessage, String content,
                        String reasoning, ObjectNode assistantMessage, List<JsonNode> toolCalls,
                        ChatOrchestrationService.TokenUsage usage) {
}
