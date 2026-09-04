package com.nora.agent.api;

/**
 * Kind of a single step in the agent ReAct loop (architecture-v2.md section 4.2).
 */
public enum ChatStepType {

    /** LLM reasoning step (chain-of-thought preceding an answer or a tool call). */
    THINK,

    /** Tool invocation step (RAG search, log tailing, automation, …). */
    TOOL
}
