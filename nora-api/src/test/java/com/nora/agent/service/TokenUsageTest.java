package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;

class TokenUsageTest {
    @Test
    void cacheAndUncachedInputsAcrossProtocols() {
        var anthropic = UpstreamLlmClient.tokenUsage(AnthropicTokenUsage.builder()
                .inputTokenCount(564).cacheReadInputTokens(155_500).cacheCreationInputTokens(200)
                .outputTokenCount(604).build());
        var openAi = UpstreamLlmClient.tokenUsage(OpenAiTokenUsage.builder().inputTokenCount(156_264)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(155_500).build())
                .outputTokenCount(604).totalTokenCount(156_868).build());
        anthropic.add(openAi);
        UpstreamLlmClient.tokenUsage(new dev.langchain4j.model.output.TokenUsage(300, 20));
        UpstreamLlmClient.tokenUsage(AnthropicTokenUsage.builder().outputTokenCount(20).build());
        UpstreamLlmClient.tokenUsage(null);
    }
}
