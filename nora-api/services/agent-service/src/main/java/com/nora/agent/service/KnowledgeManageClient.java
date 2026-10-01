package com.nora.agent.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.nora.common.response.ApiResponse;

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
    /**
     * 主动语义检索(与自动注入同一检索通道,可换关键词/调 topK 重查)。
     *
     * <p>2026-09-22(阶段 A):rag-service 返回带通道状态的结果集——
     * unavailable/degraded 明确告知模型(不要把自己的检索故障说成「资料里没有」);
     * 证据用完整 content(单块上限 1500 字符,防超大块挤占工具结果预算),
     * 而不是旧版 500 字符截断的 snippet。
     *
     * @param query 自然语言查询
     * @param topK  最大结果数(1-20,默认 8)
     * @return 面向 LLM 的命中列表;无命中/降级给可操作提示
     */
    public String search(String query, int topK) {
        return search(query, topK, null, null);
    }

    /**
     * 带范围的检索(阶段 B):指定资料库或文档列表——「只在这批资料里找」。
     *
     * @param baseId 资料库 id;null = 不限库
     * @param docIds 指定文档 id;null/空 = 不限文档
     */
    public String search(String query, int topK, Long baseId, java.util.List<Long> docIds) {
        try {
            int safeTopK = Math.max(1, Math.min(topK, 20));
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("query", query);
            body.put("topK", safeTopK);
            if (baseId != null) {
                body.put("baseId", baseId);
            }
            if (docIds != null && !docIds.isEmpty()) {
                body.put("docIds", docIds);
            }
            ApiResponse<JsonNode> envelope = restClient.post()
                    .uri("/api/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode payload = envelope.data();
            String status = payload.path("status").asText("ok");
            JsonNode results = payload.path("results");
            if ("unavailable".equals(status)) {
                return "ERROR: 知识库检索暂不可用(全部检索通道失败)——这是服务故障,"
                        + "不是「资料里没有」;请如实告知用户检索服务异常,稍后重试。";
            }
            if (results.isMissingNode() || !results.isArray() || results.isEmpty()) {
                String prefix = "degraded".equals(status)
                        ? "(部分通道降级)按当前可用通道未找到相关片段——"
                        : "(无命中)知识库中没有与「" + query + "」相关的片段——";
                return prefix + "可换更具体的关键词重试,"
                        + "或用 manage_knowledge action=list 确认文档是否已入库";
            }
            StringBuilder sb = new StringBuilder("知识库命中(共 " + results.size() + " 块,最优在前"
                    + ("degraded".equals(status) ? ";部分检索通道降级,结果可能不全" : "") + "):\n");
            for (JsonNode n : results) {
                sb.append("- [").append(n.path("docName").asText("?")).append("#")
                        .append(n.path("chunkIndex").asInt());
                if (n.hasNonNull("chunkId")) {
                    sb.append(" chunkId=").append(n.path("chunkId").asLong());
                }
                sb.append("] 分=").append(String.format("%.3f", n.path("score").asDouble()));
                String channel = n.path("matchChannel").asText("");
                if (!channel.isBlank()) {
                    sb.append(" 命中=").append(channel);
                }
                sb.append('\n').append("  ");
                // 完整证据(单块上限 1500 字符;旧版只有 500 字符 snippet)
                String content = n.path("content").asText(n.path("snippet").asText(""));
                if (content.length() > 1500) {
                    content = content.substring(0, 1500) + "…(已截断,共 " + content.length() + " 字符)";
                }
                sb.append(content).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("knowledge search failed: {}", e.getMessage());
            return "ERROR: 知识库检索失败: " + e.getMessage()
                    + "(这是服务故障,不是「资料里没有」)";
        }
    }

    /** 文档列表(含状态/块数/来源;支持按名字过滤)。 */
    public String list(String filter) {
        try {
            ApiResponse<JsonNode> envelope = restClient.get()
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
            ApiResponse<JsonNode> envelope = restClient.post()
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
            return "ERROR: " + e.getMessage() + "(先 manage_file list 确认文件 id;无提取文本的文件不能索引)";
        }
    }

    /** 删除文档及其分块。 */
    public String remove(long docId) {
        try {
            ApiResponse<JsonNode> envelope = restClient.delete()
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
            ApiResponse<JsonNode> envelope = restClient.post()
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
            ApiResponse<JsonNode> envelope = restClient.get()
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

    /** 资料库列表(阶段 B;含各库文档数)。 */
    public String listBases() {
        try {
            ApiResponse<JsonNode> envelope = restClient.get()
                    .uri("/api/rag/bases")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(无资料库)";
            }
            StringBuilder sb = new StringBuilder("资料库(共 " + envelope.data().size() + " 个):\n");
            for (JsonNode n : envelope.data()) {
                sb.append("- id=").append(n.path("id").asLong())
                        .append(" 「").append(n.path("name").asText("?")).append("」")
                        .append(n.path("isDefault").asBoolean() ? " [默认]" : "")
                        .append(" 文档 ").append(n.path("docCount").asLong()).append(" 篇");
                if (n.hasNonNull("description")) {
                    sb.append(" — ").append(n.path("description").asText(""));
                }
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("knowledge bases failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 文档停用/启用(阶段 B;停用=退出检索,保留数据与索引)。 */
    public String setEnabled(long docId, boolean enabled) {
        try {
            ApiResponse<JsonNode> envelope = restClient.post()
                    .uri("/api/rag/docs/{id}/enabled", docId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return "文档 id=" + docId + " 已" + (enabled ? "启用" : "停用(退出检索,数据保留)")
                    + ":「" + envelope.data().path("name").asText("?") + "」";
        } catch (Exception e) {
            log.warn("knowledge setEnabled failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }
}
