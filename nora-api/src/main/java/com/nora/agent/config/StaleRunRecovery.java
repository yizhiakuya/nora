package com.nora.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.nora.agent.service.ApprovalService;
import com.nora.agent.service.ChatRunFinalizer;
import com.nora.agent.service.ChatStoreService;

/**
 * 恢复上一进程的已保存过程；不自动重放工具。恢复失败时不能接受新任务，
 * 否则下一轮会在缺失执行证据的历史上继续。
 */
@Component
public class StaleRunRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final ChatStoreService chatStoreService;
    private final ApprovalService approvalService;
    private final ChatRunFinalizer finalizer;

    public StaleRunRecovery(ChatStoreService chatStoreService, ApprovalService approvalService, ChatRunFinalizer finalizer) {
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
        this.finalizer = finalizer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        finalizer.retryPending();
        var sessions = chatStoreService.recoverStaleRuns(finalizer.pendingRunIds());
        sessions.forEach(approvalService::clearPending);
        if (!sessions.isEmpty()) {
            log.info("stale chat sessions recovered on startup: {}", sessions.size());
        }
    }
}
