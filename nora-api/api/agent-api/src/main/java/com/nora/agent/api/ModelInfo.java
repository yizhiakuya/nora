package com.nora.agent.api;

/**
 * One model available for chat, as configured in agent-service.
 *
 * @param protocol  provider protocol family (e.g. {@code openai}, {@code anthropic}, {@code ollama})
 * @param modelName model identifier as expected by the provider (e.g. {@code gpt-4o-mini})
 */
public record ModelInfo(
        String protocol,
        String modelName) {
}
