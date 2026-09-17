package com.nora.agent.api;

import java.util.List;

/**
 * agent-service 的 Dubbo 契约(architecture-v2.md 5.1 节)。
 *
 * <p>提供方:services/agent-service(LangChain4j ReAct 循环、SSE 流式)。
 * 这个 Phase 1 占位只暴露模型列表;对话本身经网关({@code /api/chat/**})
 * 以 REST 消费,所以这里暂无流式方法。</p>
 */
public interface AgentService {

    /**
     * 列出 agent-service 当前配置的对话模型。
     *
     * @return 可用模型(含协议族与模型名)
     */
    List<ModelInfo> listModels();
}
