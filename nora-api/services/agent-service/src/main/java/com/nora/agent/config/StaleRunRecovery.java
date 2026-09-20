package com.nora.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.nora.agent.service.ChatStoreService;

/**
 * 启动恢复(M3-01,2026-09-20,方案 §6.4 第 6 条):
 * 把上一进程遗留的非终态对话运行(queued/running/awaiting_approval/cancelling)
 * 标记为 interrupted——不自动重放有副作用的任务;用户可在任务页看到并手动重试。
 *
 * <p>为什么不是"恢复执行":进程内编排线程已消失(activeTurns/TurnStreamRegistry
 * 均为内存态),且工具可能已产生部分副作用;重放的重复执行风险高于让用户决定。
 */
@Component
public class StaleRunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final ChatStoreService chatStoreService;

    public StaleRunRecovery(ChatStoreService chatStoreService) {
        this.chatStoreService = chatStoreService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int marked = chatStoreService.markStaleRunsInterrupted();
            if (marked > 0) {
                log.info("stale chat runs marked interrupted on startup: {}", marked);
            }
        } catch (Exception e) {
            // 表不存在(迁移未跑)或 DB 不可用:启动不阻断
            log.warn("stale run recovery skipped: {}", e.getMessage());
        }
    }
}
