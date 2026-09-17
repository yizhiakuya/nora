package com.nora.agent.api;

/**
 * 单个对话步骤的生命周期状态(SSE 流式状态)。
 */
public enum ChatStepStatus {

    /** 步骤已排队,尚未开始。 */
    PENDING,

    /** 步骤正在执行。 */
    RUNNING,

    /** 步骤成功完成。 */
    COMPLETED,

    /** 步骤失败(错误细节在 {@link ChatStepEvent#detail()})。 */
    FAILED
}
