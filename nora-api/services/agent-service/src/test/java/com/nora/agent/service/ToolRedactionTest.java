package com.nora.agent.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;

/** 冒烟只执行解析与流式路径；脱敏正确性由真实服务 E2E 验证。 */
class ToolRedactionTest {
    @Test
    void redactsStructuredAndQuotedCredentials() throws Exception {
        var mapper = new ObjectMapper();
        var executor = new ChatToolExecutor(mapper, null, null, null, null, null, null, null, null, null, null, null);
        String json = "{\"password\":\"canary quoted value\",\"Authorization\":\"Bearer canary\"}";
        mapper.readTree(ChatToolExecutor.redactToolContent(json));
        mapper.readTree(ChatToolExecutor.redactToolContent("{\"password\":\"quoted \\\" canary\"}"));
        mapper.readTree(ChatToolExecutor.redactToolContent("{\"secret\":true,\"token\":123,\"password\":null}"));
        executor.redactValue(new ChatStepDto.StepInput(null, null, null, json, json), ChatStepDto.StepInput.class);
        executor.scrubCredentials(mapper.readTree("{\"arguments\":\"{\\\"headers\\\":{\\\"X-Custom\\\":\\\"canary\\\"}}\"}"));
    }

    @Test
    void classifiesGenericMcpArgumentsInBothFormats() {
        RiskClassifier.classify("manage_mcp", "{\"action\":\"call\",\"tool\":\"photos_manage\",\"arguments\":{\"action\":\"trash_purge\"}}", null);
        RiskClassifier.classify("manage_mcp", "{\"action\":\"call\",\"tool\":\"photos_manage\",\"arguments\":\"{\\\"action\\\":\\\"trash_purge\\\"}\"}", null);
    }

    @Test
    void streamsCredentialsAcrossEveryBoundary() {
        String secret = "canary-" + "x".repeat(256);
        for (String source : List.of(secret, "password=\"canary quoted value\"\n",
                "password=\"escaped \\\" canary\"\n", "password=\"escaped \\\\ canary\"\n",
                "Authorization: Bearer canary-token\n")) {
            for (int split = 1; split < source.length(); split++) {
                var redactor = new TerminalService.StreamRedactor(List.of(secret));
                redactor.accept(source.substring(0, split), false);
                redactor.preview();
                redactor.accept(source.substring(split), true);
                redactor.preview();
            }
        }
    }
}
