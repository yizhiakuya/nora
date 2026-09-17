package com.nora.agent.api;

/**
 * agent-service 中配置的一个可用对话模型。
 *
 * @param protocol  provider 协议族(如 {@code openai}、{@code anthropic}、{@code ollama})
 * @param modelName provider 期望的模型标识(如 {@code gpt-4o-mini})
 */
public record ModelInfo(
        String protocol,
        String modelName) {
}
