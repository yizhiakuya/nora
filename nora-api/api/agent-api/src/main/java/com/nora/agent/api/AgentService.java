package com.nora.agent.api;

import java.util.List;

/**
 * Dubbo contract for agent-service (per architecture-v2.md section 5.1).
 *
 * <p>Provider: services/agent-service (LangChain4j ReAct loop, SSE streaming).
 * This Phase 1 placeholder only exposes model listing; chat itself is
 * consumed REST-wise through the gateway ({@code /api/chat/**}), so no
 * streaming methods appear here yet.</p>
 */
public interface AgentService {

    /**
     * Lists the chat models currently configured in agent-service.
     *
     * @return available models with protocol family and model name
     */
    List<ModelInfo> listModels();
}
