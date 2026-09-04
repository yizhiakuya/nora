package com.nora.agent.api;

/**
 * Lifecycle status of a single chat step (SSE streaming states).
 */
public enum ChatStepStatus {

    /** Step queued, not started yet. */
    PENDING,

    /** Step currently executing. */
    RUNNING,

    /** Step finished successfully. */
    COMPLETED,

    /** Step failed (error detail in {@link ChatStepEvent#detail()}). */
    FAILED
}
