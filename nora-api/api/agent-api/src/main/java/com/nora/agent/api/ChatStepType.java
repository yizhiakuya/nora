package com.nora.agent.api;

/**
 * agent ReAct 循环中单步的种类(architecture-v2.md 4.2 节)。
 */
public enum ChatStepType {

    /** LLM 推理步骤(回答或工具调用之前的思维链)。 */
    THINK,

    /** 工具调用步骤(RAG 检索、日志 tail、自动化等)。 */
    TOOL
}
