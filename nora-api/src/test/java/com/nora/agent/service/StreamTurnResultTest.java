package com.nora.agent.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class StreamTurnResultTest {
    @Test
    void modelRequestSettings() throws Exception {
        var mapper = new ObjectMapper();
        var settings = mapper.readValue("{\"m\":{\"protocol\":\"responses\"}}",
                ModelProviderService.ModelSettings.class);
        mapper.writeValueAsString(settings);
        ModelProviderService.parseModelSettingsStatic(mapper.writeValueAsString(settings)).forModel("m");
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        for (String model : List.of("gpt-5", "o3", "deepseek", "generic")) {
            for (String protocol : List.of("openai", "responses")) {
                var llm = new ResolvedLlm("http://localhost", "key", model, protocol, "low", 128000L, null);
                client.reasoningOptions(ResolvedLlm.withLevel(llm, "minimal"));
                new ContextBudget(llm.contextWindow()).targetTokens(false);
            }
        }
    }

    @Test
    void streamResultCompletionPaths() {
        var mapper = new ObjectMapper();
        for (var result : List.of(
                new StreamTurnResult(false, null, "", "", null, List.of(), null),
                new StreamTurnResult(false, null, " \n", "仅有思考", null, List.of(), null),
                new StreamTurnResult(false, null, "已打开", "", null, List.of(), null),
                new StreamTurnResult(false, null, "", "", null, List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call").name("test").arguments("{}").build()), null),
                new StreamTurnResult(true, "上游连接失败", "", "", null, List.of(), null))) {
            result.failed();
            result.errorMessage();
            result.hasOnlyReasoning();
            result.retryable();
            result.isFinalAnswer();
        }
        for (var state : StreamTurnResult.EndState.values()) {
            var result = new StreamTurnResult(false, null, "未完成正文", "", null,
                    List.of(), null, state, "commentary");
            result.failed();
            result.retryable();
            result.isFinalAnswer();
            new StreamTurnResult(true, null, "", "", null, List.of(), null, state, null).retryable();
        }
        for (int status : List.of(400, 401, 408, 429, 500, 503)) {
            new StreamTurnResult(true, "raw HTTP error", "", "", null, List.of(), null,
                    StreamTurnResult.EndState.FAILED, null, status).retryable();
            new StreamTurnResult(true, "raw HTTP error", "已输出", "", null, List.of(), null,
                    StreamTurnResult.EndState.FAILED, null, status).retryable();
        }
    }
}
