package com.nora.file.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.rag.service.KnowledgeDocService;

/** 升级时完成旧 outbox 的未送达状态；新操作不再产生投递记录。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyRagSyncRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final KnowledgeDocService knowledge;
    private final TransactionTemplate tx;

    public LegacyRagSyncRecovery(JdbcTemplate jdbc, KnowledgeDocService knowledge, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (long fileId : jdbc.queryForList("SELECT file_id FROM pending_rag_sync ORDER BY file_id", Long.class)) {
            tx.executeWithoutResult(status -> {
                // Same lock order as local file mutations, then re-read the still-pending version.
                jdbc.queryForList("SELECT id FROM file_item WHERE id = ? FOR UPDATE", Long.class, fileId);
                jdbc.query("SELECT mode, version FROM pending_rag_sync WHERE file_id = ? FOR UPDATE", rs -> {
                    knowledge.applyLifecycle(fileId, rs.getString("mode"), rs.getLong("version"));
                    jdbc.update("DELETE FROM pending_rag_sync WHERE file_id = ?", fileId);
                }, fileId);
            });
        }
    }
}
