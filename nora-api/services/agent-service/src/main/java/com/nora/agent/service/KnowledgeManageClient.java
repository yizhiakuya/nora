package com.nora.agent.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 知识库主动检索与管理,供 agent 的 {@code search_knowledge}(只读检索)与
 * {@code manage_knowledge}(list/index/remove/reindex/stats)工具。
 *
 * <p>动机(2026-09-18 工具设计分析):RAG 此前只有「每轮自动注入」一条路——
 * 自动召回不佳时 agent 无法换关键词重查,索引管理也只有 UI 能做。
 */
@Service
public class KnowledgeManageClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeManageClient.class);

    private final RestClient restClient;

    public KnowledgeManageClient(RestClient ragServiceRestClient) {
        this.restClient = ragServiceRestClient;
    }

    /**
     * 主动语义检索(与自动注入同一检索通道,可换关键词/调 topK 重查)。
     *
     * @param query 自然语言查询
     * @param topK  最大结果数(1-20,默认 8)
     * @return 面向 LLM 的命中列表;无命中给可操作提示
     */
    public String search(String query, int topK) {
        try {
            int safeTopK = Math.max(1, Math.min(topK, 20));
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "topK", safeTopK))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(无命中)知识库中没有与「" + query + "」相关的片段——可换更具体的关键词重试,"
                        + "或用 manage_knowledge action=list 确认文档是否已入库";
            }
            StringBuilder sb = new StringBuilder("知识库命中(共 " + envelope.data().size() + " 块,最优在前):\n");
            for (JsonNode n : envelope.data()) {
                sb.append("- [").append(n.path("docName").asText("?")).append("#")
                        .append(n.path("chunkIndex").asInt()).append("] 分=")
                        .append(String.format("%.3f", n.path("score").asDouble()))
                        .append('\n').append("  ").append(n.path("snippet").asText("")).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("knowledge search failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 文档列表(含状态/块数/来源;支持按名字过滤)。 */
    public String list(String filter) {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/rag/docs")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (JsonNode n : envelope.data()) {
                String name = n.path("name").asText("?");
                if (filter != null && !filter.isBlank()
                        && !name.toLowerCase().contains(filter.toLowerCase())) {
                    continue;
                }
                sb.append("id=").append(n.path("id").asLong())
                        .append(" | ").append(name)
                        .append(" | 来源=").append(n.path("source").asText("?"))
                        .append(" | 块=").append(n.path("chunks").asInt())
                        .append(" | 状态=").append(n.path("status").asText("?"))
                        .append(" | 更新=").append(n.path("updatedAt").asText("?"))
                        .append('\n');
                shown++;
            }
            if (shown == 0) {
                return filter == null || filter.isBlank()
                        ? "(知识库为空)可让用户在知识库页上传文档,或对文件中心的文件执行「加入知识库」"
                        : "(没有名称包含「" + filter + "」的文档)用不带 filter 的 list 看全部";
            }
            return "知识库文档(共 " + shown + " 篇):\n" + sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("knowledge list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 索引一个工作台文件(先确认文件存在,再触发切分+嵌入)。
     *
     * @param fileId 文件中心文件 id
     * @param name   可选展示名(默认取文件名)
     */
    public String indexFile(long fileId, String name) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("fileId", fileId);
            if (name != null && !name.isBlank()) {
                body.put("name", name);
            }
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/rag/index")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            return "已索引为知识库文档 id=" + d.path("id").asLong()
                    + " name=" + d.path("name").asText("?")
                    + " 块数=" + d.path("chunks").asInt()
                    + " 状态=" + d.path("status").asText("?")
                    + "。现在可用 search_knowledge 检索它";
        } catch (Exception e) {
            log.warn("knowledge index failed for file {}: {}", fileId, e.getMessage());
            return "ERROR: " + e.getMessage() + "(先 read_file list 确认文件 id;无提取文本的文件不能索引)";
        }
    }

    /** 删除文档及其分块。 */
    public String remove(long docId) {
        try {
            Envelope<JsonNode> envelope = restClient.delete()
                    .uri("/api/rag/docs/{id}", docId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            int deleted = envelope.data() == null ? 0 : envelope.data().path("deleted").asInt(0);
            return deleted > 0
                    ? "已删除知识库文档 id=" + docId + "(其全部块同时移除,文件中心原文件不受影响)"
                    : "ERROR: 文档 id=" + docId + " 不存在(用 action=list 确认)";
        } catch (Exception e) {
            log.warn("knowledge remove failed for doc {}: {}", docId, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 重建文档向量(嵌入模型变更后刷新 / 恢复失败文档)。 */
    public String reindex(long docId) {
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/rag/docs/{id}/reindex", docId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            return "已重建文档 id=" + docId + "(" + d.path("name").asText("?") + ")的向量,块数="
                    + d.path("chunks").asInt() + " 状态=" + d.path("status").asText("?");
        } catch (Exception e) {
            log.warn("knowledge reindex failed for doc {}: {}", docId, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 索引统计快照。 */
    public String stats() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/rag/index/stats")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            return "知识库统计:文档 " + d.path("totalDocs").asLong()
                    + " 篇 / 块 " + d.path("totalChunks").asLong()
                    + " 个 / 向量维度 " + d.path("vectorDim").asInt()
                    + " / 嵌入模型 " + d.path("model").asText("?")
                    + " / 处理中 " + d.path("pendingDocs").asLong()
                    + " / 最后更新 " + d.path("lastUpdate").asText("?")
                    + (d.path("vectorReady").asBoolean() ? "" : " / ⚠ 向量组件未就绪");
        } catch (Exception e) {
            log.warn("knowledge stats failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
