package com.nora.agent.service;

import dev.langchain4j.invocation.InvocationParameters;

/** 运行信息不属于模型可填写的工具参数。 */
record ToolCallContext(String sessionId, String stepId, ChatToolExecutor.LiveOutput liveOutput,
                       ChatOrchestrationService.ChatEventConsumer events, boolean unattended) {
    static ToolCallContext from(InvocationParameters parameters) {
        return parameters.get("nora.tool");
    }
}
