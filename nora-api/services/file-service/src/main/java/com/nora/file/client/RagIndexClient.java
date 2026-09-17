package com.nora.file.client;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.nora.file.api.FileItem;

/**
 * 请求 rag-service 索引文件的发后即忘 REST 客户端。失败只记日志并吞掉,
 * 绝不影响调用方。
 */
@Component
public class RagIndexClient {

    private static final Logger log = LoggerFactory.getLogger(RagIndexClient.class);

    private final String ragBaseUrl;

    public RagIndexClient(@Value("${nora.rag.base-url:http://localhost:8082}") String ragBaseUrl) {
        this.ragBaseUrl = ragBaseUrl;
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
     * 该文件对应的知识库文档。fire-and-forget——rag 不可达不阻断文件操作
     * (数据一致性可稍后人工修复,但用户操作必须成功)。
     *
     * @param fileId 文件 id
     * @param mode   soft(文件删除)/ restore(回收站恢复)/ purge(永久删除)
     */
    @Async
    public void notifyLifecycleAsync(long fileId, String mode) {
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
        } catch (Exception ex) {
            log.error("Failed to notify rag-service lifecycle {} for file {}: {}", mode, fileId, ex.getMessage());
        }
    }
}
