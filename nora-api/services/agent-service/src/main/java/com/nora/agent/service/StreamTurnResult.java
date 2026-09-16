package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** One streamed model turn: forwarded content/reasoning plus accumulated tool_calls. */
record StreamTurnResult(boolean failed, String errorMessage, String content,
                        String reasoning, ObjectNode assistantMessage, List<JsonNode> toolCalls,
                        ChatOrchestrationService.TokenUsage usage) {
}
