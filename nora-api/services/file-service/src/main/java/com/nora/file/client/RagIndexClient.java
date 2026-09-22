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
     * 文件生命周期联动(2026-09-17;2026-09-20 R02 重构)。
     *
     * <p>语义:先**持久化**待处理通知(失败也不会丢),再尝试立即送达;
     * 成功即删,失败留给 {@link #retryPending()} 重试——文件操作本身永不因
     * rag 不可达而失败。
     *
     * <p><b>最终状态一致性(R02 修复)</b>:表按 {@code file_id} 唯一,保存
     * **最新目标状态**(desired_state)+ **单调版本**(version,每次变化 +1)。
     * 送达时携带 version;接收端(rag)按版本条件确认——「删除→恢复→删除」
     * 时在途的旧请求(版本落后)不会复活旧状态。重试只投递当前行的最新
     * 目标状态,而不是历史上出现过的任意 mode。
     *
     * @param fileId 文件 id
     * @param mode   soft(文件删除)/ restore(回收站恢复)/ purge(永久删除)
     */
    @Async
    public void notifyLifecycleAsync(long fileId, String mode) {
        String desired = switch (mode) {
            case "soft" -> "deleted";
            case "restore" -> "present";
            case "purge" -> "purged";
            default -> mode;
        };
        long version = 1;
        try {
            // 版本号来自独立计数器表(2026-09-22 阶段 A,方案 §5.4):此前版本
            // 存在 pending 行上,送达成功删行后下次从 1 重来——「删除→恢复→
            // 删除」的第 2、3 次通知被 rag 侧按「不更大」拒绝(实测 applied 停在 1)。
            // 现在先原子分配全局单调版本,再 upsert 投递队列(version 只是本次快照)。
            version = jdbcTemplate.queryForObject(
                    "INSERT INTO file_lifecycle_version (file_id, next_version) VALUES (?, 2) "
                            + "ON CONFLICT (file_id) DO UPDATE SET next_version = file_lifecycle_version.next_version + 1, updated_at = now() "
                            + "RETURNING next_version - 1",
                    Long.class, fileId);
            // upsert:每文件一行;目标状态与版本同事务推进
            jdbcTemplate.update(
                    "INSERT INTO pending_rag_sync (file_id, mode, desired_state, version, attempts, next_attempt_at) "
                            + "VALUES (?, ?, ?, ?, 0, now()) "
                            + "ON CONFLICT (file_id) DO UPDATE SET mode = EXCLUDED.mode, "
                            + "desired_state = EXCLUDED.desired_state, version = EXCLUDED.version, "
                            + "attempts = 0, next_attempt_at = now(), last_error = NULL",
                    fileId, mode, desired, version);
        } catch (Exception e) {
            // 落库失败(极少):退回纯发后即忘,不阻断调用方
            log.warn("failed to persist pending rag sync for file {} mode {}: {}", fileId, mode, e.getMessage());
        }
        if (deliverLifecycle(fileId, mode, version)) {
            clearPendingIfCurrent(fileId, version);
        }
    }

    /**
     * 实际发送一次生命周期通知;true = 送达(2xx)。
     *
     * <p>R01(2026-09-20 修复):此前 {@code onStatus} 只记日志**不抛错**——
     * rag-service 返回 503 时 RestClient 视为正常完成,方法仍返回 true,
     * 待重试记录被清掉,通知永久丢失。现在非 2xx 一律抛
     * {@link RestClientResponseException}(由 onStatus 处理器内抛出),外层
     * catch 返回 false,待重试记录保留、由定时重试继续投递。
     * 4xx(如文档不存在/参数错误)同样不清记录——让用户可见"同步中/待重试",
     * 而不是静默当作已同步(与方案 §12 R01 通过标准一致)。
     *
     * <p>R02:请求携带 {@code version};rag 侧只接受 ≥ 已记录版本的请求,
     * 晚到的旧版本请求被拒绝(不复活旧状态)。
     */
    private boolean deliverLifecycle(long fileId, String mode, long version) {
        try {
            RestClient.create(ragBaseUrl)
                    .post()
                    .uri("/api/rag/docs/by-file/{fileId}?mode={mode}&version={version}", fileId, mode, version)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("rag-service rejected lifecycle {} v{} for file {} (status {}): {}",
                                mode, version, fileId, res.getStatusCode(), body);
                        // 必须抛出:不抛会被当作 delivered=true,待重试记录被清、通知丢失(R01)
                        throw new org.springframework.web.client.RestClientException(
                                "rag lifecycle notify failed: status " + res.getStatusCode().value());
                    })
                    .toBodilessEntity();
            log.info("Notified rag-service: file {} lifecycle={} v{}", fileId, mode, version);
            return true;
        } catch (Exception ex) {
            log.error("Failed to notify rag-service lifecycle {} v{} for file {}: {}",
                    mode, version, fileId, ex.getMessage());
            return false;
        }
    }

    /**
     * 仅在版本未被更新时清待重试行(条件删除):送达期间文件又发生了新的
     * 生命周期变化(version 已推进)时,新状态的记录必须保留(R02)。
     */
    private void clearPendingIfCurrent(long fileId, long version) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM pending_rag_sync WHERE file_id = ? AND version = ?", fileId, version);
            if (deleted == 0) {
                log.info("pending rag sync for file {} advanced during delivery; keeping newer state", fileId);
            }
        } catch (Exception e) {
            log.warn("failed to clear pending rag sync for file {}: {}", fileId, e.getMessage());
        }
    }

    /**
     * 定时重试未送达的生命周期通知(30s 周期;指数退避封顶 10 分钟)。
     * 只投递每文件的**最新目标状态**(R02);成功即条件删行。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retryPending() {
        List<Object[]> pendingRows;
        try {
            pendingRows = jdbcTemplate.query(
                    "SELECT file_id, mode, version FROM pending_rag_sync WHERE next_attempt_at <= now() ORDER BY file_id LIMIT 20",
                    (rs, rowNum) -> new Object[]{rs.getLong("file_id"), rs.getString("mode"), rs.getLong("version")});
        } catch (Exception e) {
            log.warn("pending rag sync scan failed: {}", e.getMessage());
            return;
        }
        for (Object[] row : pendingRows) {
            long fileId = (Long) row[0];
            String mode = (String) row[1];
            long version = (Long) row[2];
            if (mode == null) {
                continue;
            }
            if (deliverLifecycle(fileId, mode, version)) {
                clearPendingIfCurrent(fileId, version);
                continue;
            }
            // 失败:退避重试(30s * 2^attempts,封顶 10 分钟);仅当版本未变时退避
            // (版本已变=有新状态等待,由新记录自己的节奏处理)
            try {
                jdbcTemplate.update(
                        "UPDATE pending_rag_sync SET attempts = attempts + 1, "
                                + "last_error = 'delivery failed', "
                                + "next_attempt_at = now() + (LEAST(600, 30 * POWER(2, attempts)) || ' seconds')::interval "
                                + "WHERE file_id = ? AND version = ?",
                        fileId, version);
            } catch (Exception e) {
                log.warn("failed to back off pending rag sync {}: {}", fileId, e.getMessage());
            }
        }
    }
}
