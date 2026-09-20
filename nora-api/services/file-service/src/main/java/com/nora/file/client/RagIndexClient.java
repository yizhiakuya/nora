package com.nora.file.client;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.nora.file.api.FileItem;

/**
 * 请求 rag-service 索引文件的发后即忘 REST 客户端。失败只记日志并吞掉,
 * 绝不影响调用方。
 *
 * <p><b>生命周期通知的自动补偿(2026-09-20 修复)</b>:删除/恢复/永久删除
 * 的通知失败不再永远丢失——先落 {@code pending_rag_sync} 表,成功即删,
 * 失败由 {@link #retryPending()} 定时重试(指数退避)。覆盖「文档早已索引,
 * 删除时 RAG 不可用,随后 RAG 恢复」的场景:通知会在 RAG 恢复后送达。
 */
@Component
public class RagIndexClient {

    private static final Logger log = LoggerFactory.getLogger(RagIndexClient.class);

    private final String ragBaseUrl;
    private final JdbcTemplate jdbcTemplate;

    public RagIndexClient(@Value("${nora.rag.base-url:http://localhost:8082}") String ragBaseUrl,
                          JdbcTemplate jdbcTemplate) {
        this.ragBaseUrl = ragBaseUrl;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 异步 POST {@code {fileId}} 到 {@code {ragBaseUrl}/api/rag/index}。
     * 跑在 Spring 异步执行器上;任何错误只记日志。
     *
     * @param file 要索引的文件
     */
    @Async
    public void triggerIndexAsync(FileItem file) {
        try {
            RestClient.create(ragBaseUrl)
                    .post()
                    .uri("/api/rag/index")
                    .body(new IndexRequest(file.id()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("rag-service rejected index request for file {} (status {}): {}",
                                file.id(), res.getStatusCode(), body);
                    })
                    .toBodilessEntity();
            log.info("Triggered rag-service indexing for file {}", file.id());
        } catch (Exception ex) {
            // 发后即忘:rag-service 挂了绝不能弄断调用方
            log.error("Failed to trigger rag-service indexing for file {}: {}", file.id(), ex.getMessage());
        }
    }

    /** {@code POST /api/rag/index} 的请求体。 */
    record IndexRequest(Long fileId) {
    }

    /**
     * 文件生命周期联动(2026-09-17):通知 rag-service 软删/恢复/永久删除
     * 该文件对应的知识库文档。
     *
     * <p>语义(2026-09-20 修复后):先**持久化**待处理通知(失败也不会丢),
     * 再尝试立即送达;成功即删,失败留给 {@link #retryPending()} 重试——
     * 文件操作本身永不因 rag 不可达而失败。
     *
     * @param fileId 文件 id
     * @param mode   soft(文件删除)/ restore(回收站恢复)/ purge(永久删除)
     */
    @Async
    public void notifyLifecycleAsync(long fileId, String mode) {
        // 持久化(upsert):同一 (file, mode) 覆盖——反复删除/恢复时旧通知
        // 已被新状态取代,送达最新一次即可
        try {
            jdbcTemplate.update(
                    "INSERT INTO pending_rag_sync (file_id, mode) VALUES (?, ?) "
                            + "ON CONFLICT (file_id, mode) DO UPDATE SET attempts = 0, "
                            + "next_attempt_at = now(), last_error = NULL",
                    fileId, mode);
        } catch (Exception e) {
            // 落库失败(极少):退回纯发后即忘,不阻断调用方
            log.warn("failed to persist pending rag sync for file {} mode {}: {}", fileId, mode, e.getMessage());
        }
        if (deliverLifecycle(fileId, mode)) {
            clearPending(fileId, mode);
        }
    }

    /** 实际发送一次生命周期通知;true = 送达(2xx)。 */
    private boolean deliverLifecycle(long fileId, String mode) {
        try {
            RestClient.create(ragBaseUrl)
                    .post()
                    .uri("/api/rag/docs/by-file/{fileId}?mode={mode}", fileId, mode)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("rag-service rejected lifecycle {} for file {} (status {}): {}",
                                mode, fileId, res.getStatusCode(), body);
                    })
                    .toBodilessEntity();
            log.info("Notified rag-service: file {} lifecycle={}", fileId, mode);
            return true;
        } catch (Exception ex) {
            log.error("Failed to notify rag-service lifecycle {} for file {}: {}", mode, fileId, ex.getMessage());
            return false;
        }
    }

    private void clearPending(long fileId, String mode) {
        try {
            jdbcTemplate.update("DELETE FROM pending_rag_sync WHERE file_id = ? AND mode = ?", fileId, mode);
        } catch (Exception e) {
            log.warn("failed to clear pending rag sync for file {} mode {}: {}", fileId, mode, e.getMessage());
        }
    }

    /**
     * 定时重试未送达的生命周期通知(30s 周期;指数退避封顶 10 分钟)。
     * 成功即删行;RAG 恢复后积压的删除/恢复通知会在一个周期内送达。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retryPending() {
        List<long[]> pendingRows;
        try {
            pendingRows = jdbcTemplate.query(
                    "SELECT id, file_id FROM pending_rag_sync WHERE next_attempt_at <= now() ORDER BY id LIMIT 20",
                    (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("file_id")});
        } catch (Exception e) {
            log.warn("pending rag sync scan failed: {}", e.getMessage());
            return;
        }
        for (long[] row : pendingRows) {
            long id = row[0];
            long fileId = row[1];
            String mode = null;
            try {
                mode = jdbcTemplate.queryForObject(
                        "SELECT mode FROM pending_rag_sync WHERE id = ?", String.class, id);
            } catch (Exception e) {
                continue; // 行已消失(另一实例处理过)
            }
            if (mode == null) {
                continue;
            }
            if (deliverLifecycle(fileId, mode)) {
                try {
                    jdbcTemplate.update("DELETE FROM pending_rag_sync WHERE id = ?", id);
                } catch (Exception e) {
                    log.warn("failed to delete delivered pending rag sync {}: {}", id, e.getMessage());
                }
                continue;
            }
            // 失败:退避重试(30s * 2^attempts,封顶 10 分钟)
            try {
                jdbcTemplate.update(
                        "UPDATE pending_rag_sync SET attempts = attempts + 1, "
                                + "last_error = 'delivery failed', "
                                + "next_attempt_at = now() + (LEAST(600, 30 * POWER(2, attempts)) || ' seconds')::interval "
                                + "WHERE id = ?",
                        id);
            } catch (Exception e) {
                log.warn("failed to back off pending rag sync {}: {}", id, e.getMessage());
            }
        }
    }
}
