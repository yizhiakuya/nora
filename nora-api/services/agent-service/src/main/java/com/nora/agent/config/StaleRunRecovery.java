package com.nora.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.ApprovalService;

/**
 * 恢复上一进程的已保存过程；不自动重放工具。恢复失败时不能接受新任务，
 * 否则下一轮会在缺失执行证据的历史上继续。
 */
@Component
public class StaleRunRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final ChatStoreService chatStoreService;
    private final ApprovalService approvalService;

    public StaleRunRecovery(ChatStoreService chatStoreService, ApprovalService approvalService) {
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var sessions = chatStoreService.recoverStaleRuns();
        sessions.forEach(approvalService::clearPending);
        if (!sessions.isEmpty()) {
            log.info("stale chat sessions recovered on startup: {}", sessions.size());
        }
    }
}
