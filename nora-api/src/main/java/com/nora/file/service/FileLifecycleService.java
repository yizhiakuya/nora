package com.nora.file.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.rag.service.KnowledgeDocService;

/** 文件和知识库共用一个事务；磁盘清理在提交后进行。 */
@Service
public class FileLifecycleService {
    private final FileStorageService files;
    private final KnowledgeDocService knowledge;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FileLifecycleService(FileStorageService files, KnowledgeDocService knowledge, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.files = files;
        this.knowledge = knowledge;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<Long> delete(List<Long> ids) {
        return tx.execute(status -> {
            var changed = files.delete(ids);
            for (long id : changed) { knowledge.softDeleteByFileId(id); clearLegacyPending(id); }
            return changed;
        });
    }

    public List<Long> restore(List<Long> ids) {
        return tx.execute(status -> {
            var changed = files.restore(ids);
            for (long id : changed) { knowledge.restoreByFileId(id); clearLegacyPending(id); }
            return changed;
        });
    }

    public List<Long> purge(List<Long> ids) {
        var purged = tx.execute(status -> {
            var rows = files.purgeRows(ids);
            for (var row : rows) { knowledge.purgeByFileId(row.id()); clearLegacyPending(row.id()); }
            return rows;
        });
        files.deleteFromDisk(purged);
        return purged.stream().map(FileStorageService.PurgedItem::id).toList();
    }

    private void clearLegacyPending(long id) { jdbc.update("DELETE FROM pending_rag_sync WHERE file_id = ?", id); }
}
