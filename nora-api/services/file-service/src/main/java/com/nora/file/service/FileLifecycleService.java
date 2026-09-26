package com.nora.file.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.common.logging.TraceContext;
import com.nora.file.client.RagIndexClient;

/**
 * 文件生命周期编排(F1,2026-09-26):把「文件状态变更 + RAG 通知入队」放进
 * **同一数据库事务**提交,HTTP 投递留在事务提交后。
 *
 * <p>为什么需要(审查报告 F1):此前 FileController 先改文件状态、再调
 * {@code @Async notifyLifecycleAsync}——两次独立提交之间进程退出会永久丢
 * 通知;乱序的旧动作还会用低版本覆盖待发送行里更新的目标状态(实测「恢复
 * v2 已入队后被旧删除 v1 覆盖并清除」)。现在:
 * <ol>
 *   <li>事务内:文件状态变更(软删/恢复/清空)与通知入队(版本分配 +
 *       待发送行,见 {@link RagIndexClient#enqueueLifecycle})一起原子提交
 *       ——要么都生效,要么都回滚;</li>
 *   <li>事务外:投递(HTTP,可能慢/失败),失败由 pending_rag_sync 定时
 *       重试兜底——文件操作本身永不因 rag 不可达而失败;</li>
 *   <li>清空回收站的磁盘删除同样放在事务提交后(避免「事务回滚但磁盘文件
 *       已删」)。</li>
 * </ol>
 */
@Service
public class FileLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(FileLifecycleService.class);

    private final FileStorageService fileStorageService;
    private final RagIndexClient ragIndexClient;
    private final TransactionTemplate txTemplate;

    public FileLifecycleService(FileStorageService fileStorageService,
                                RagIndexClient ragIndexClient,
                                TransactionTemplate txTemplate) {
        this.fileStorageService = fileStorageService;
        this.ragIndexClient = ragIndexClient;
        this.txTemplate = txTemplate;
    }

    /**
     * 软删文件(进回收站)+ 入队 RAG 软删通知(同事务)。
     *
     * @return 实际被软删的 id(未变化的 id 不发通知)
     */
    public List<Long> delete(List<Long> ids) {
        List<Long> changed = txTemplate.execute(txStatus -> {
            List<Long> deleted = fileStorageService.delete(ids);
            for (Long id : deleted) {
                ragIndexClient.enqueueLifecycle(id, "soft");
            }
            return deleted;
        });
        deliverAfterCommit(changed);
        return changed == null ? List.of() : changed;
    }

    /**
     * 从回收站恢复 + 入队 RAG 恢复通知(同事务)。
     *
     * @return 实际被恢复的 id
     */
    public List<Long> restore(List<Long> ids) {
        List<Long> changed = txTemplate.execute(txStatus -> {
            List<Long> restored = fileStorageService.restore(ids);
            for (Long id : restored) {
                ragIndexClient.enqueueLifecycle(id, "restore");
            }
            return restored;
        });
        deliverAfterCommit(changed);
        return changed == null ? List.of() : changed;
    }

    /**
     * 永久删除(回收站清空)+ 入队 RAG 永久删除通知(同事务);
     * 磁盘清理与通知投递都在提交后。
     *
     * @return 实际被清除的 id
     */
    public List<Long> purge(List<Long> ids) {
        List<FileStorageService.PurgedItem> purged = txTemplate.execute(txStatus -> {
            List<FileStorageService.PurgedItem> rows = fileStorageService.purgeRows(ids);
            for (FileStorageService.PurgedItem row : rows) {
                ragIndexClient.enqueueLifecycle(row.id(), "purge");
            }
            return rows;
        });
        if (purged == null || purged.isEmpty()) {
            return List.of();
        }
        // 磁盘删除是 best-effort,失败只记日志(数据行已清)
        fileStorageService.deleteFromDisk(purged);
        List<Long> purgedIds = purged.stream().map(FileStorageService.PurgedItem::id).toList();
        deliverAfterCommit(purgedIds);
        return purgedIds;
    }

    /** 事务提交后异步投递各文件的待发送通知(失败留给定时重试)。 */
    private void deliverAfterCommit(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        // 捕获当前请求的 traceId,传给异步投递线程(MDC 不跨线程,不加会断链)
        String traceId = TraceContext.traceId();
        for (Long id : fileIds) {
            try {
                ragIndexClient.deliverPendingAsync(id, traceId);
            } catch (Exception e) {
                // 投递调度失败不影响文件操作本身(行已入队,定时重试兜底)
                log.warn("failed to schedule lifecycle delivery for file {}: {}", id, e.getMessage());
            }
        }
    }
}
