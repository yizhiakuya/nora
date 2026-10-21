package com.nora.agent.service;

import org.junit.jupiter.api.Test;

class ModelCapabilityRegistryTest {
    @Test
    void malformedImagesAndConfigurationChangesDoNotShareRejection() {
        var registry = new ModelCapabilityRegistry();
        var original = new ResolvedLlm("http://localhost/v1", "credential-a", "model", "anthropic", null, 128000L, null);
        registry.visionAllowed(original, 1);
        ModelCapabilityRegistry.isVisionUnsupported("invalid image encoding");
        ModelCapabilityRegistry.isGenericUpstream400("Upstream error: 400");
        ModelCapabilityRegistry.isVisionUnsupported("model does not support image input");
        registry.markVisionRejected(original);
        registry.visionAllowed(original, 1);
        registry.visionAllowed(new ResolvedLlm(original.baseUrl(), "credential-b", original.model(), original.protocol(), null, 128000L, null), 1);
        registry.visionAllowed(new ResolvedLlm(original.baseUrl(), original.apiKey(), original.model(), "openai", null, 128000L, null), 1);
    }
}
