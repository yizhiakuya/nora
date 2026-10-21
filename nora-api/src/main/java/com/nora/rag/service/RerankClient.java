package com.nora.rag.service;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.rag.api.RetrievalResult;
import com.nora.rag.config.RetrievalProperties;

/**
 * 可选重排(阶段 B,方案 §6.2)。
 *
 * <p>融合(RRF)之后、父块合并之前对候选做 cross-encoder 重排——RRF 是
 * 基于排名的近似,重排模型直接看 (query, 文档) 对,精度更高但更慢。
 *
 * <p>设计取舍(对齐方案):
 * <ul>
 *   <li><b>默认关闭</b>({@code nora.retrieval.rerank-enabled=false}):
 *       是否开启由质量提升和耗时共同决定,不由代码单方面决定;</li>
 *   <li><b>异常保留融合结果并标记降级</b>:重排失败(超时/限流/格式变化)
 *       绝不丢结果——调用方拿到的仍是融合排序,只是 rerank 状态标记失败;</li>
 *   <li>接入"既有模型供应商支持的标准接口":复用 Jina 的 /v1/rerank
 *       (与嵌入同一 provider 与 API key),不引入新依赖。</li>
 * </ul>
 *
 * <p>请求形态(Jina rerank API):
 * <pre>
 * POST {base}/rerank
 * {"model": "...", "query": "...", "documents": ["..."], "top_n": N}
 * → {"results": [{"index": 0, "relevance_score": 0.93}, ...]}
 * </pre>
 */
class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** 重排请求超时(重排是可选增益,不能拖垮检索主链路)。 */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final RetrievalProperties properties;
    private final com.nora.rag.config.EmbeddingProperties embeddingProperties;
    /** 出站代理(与 EmbeddingService 同款:注入的配置属性,而非运行时 holder)。 */
    private final com.nora.common.http.ProxyProperties proxyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    RerankClient(RetrievalProperties properties,
                 com.nora.rag.config.EmbeddingProperties embeddingProperties) {
        this(properties, embeddingProperties, null);
    }

    RerankClient(RetrievalProperties properties,
                 com.nora.rag.config.EmbeddingProperties embeddingProperties,
                 com.nora.common.http.ProxyProperties proxyProperties) {
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
        this.proxyProperties = proxyProperties != null
                ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
    }

    /** 重排是否启用(默认 false;开启需配置项 + API key)。 */
    boolean enabled() {
        return properties.rerankEnabled()
                && embeddingProperties.configured()
                && properties.rerankModel() != null && !properties.rerankModel().isBlank();
    }

    /** 一次重排的结果;失败时 ok=false + 原因,调用方保留融合排序。 */
    record RerankOutcome(boolean ok, List<RetrievalResult> results, String error) {
    }

    /**
     * 对候选做重排。候选过少(&lt;=2)或未启用时原样返回(标记 ok)。
     */
    RerankOutcome rerank(String query, List<RetrievalResult> candidates) {
        if (candidates.size() <= 2) {
            return new RerankOutcome(true, candidates, null);
        }
        try {
            // 文档用证据正文(与融合阶段一致);截断防超长请求
            List<String> documents = new ArrayList<>(candidates.size());
            for (RetrievalResult r : candidates) {
                String text = r.content() == null ? r.snippet() : r.content();
                if (text == null) {
                    text = "";
                }
                documents.add(text.length() > 8000 ? text.substring(0, 8000) : text);
            }
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.rerankModel());
            body.put("query", query);
            ArrayNode docs = body.putArray("documents");
            for (String d : documents) {
                docs.add(d);
            }
            body.put("top_n", candidates.size());

            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            // 代理与 EmbeddingService 同款:ProxySupport 按目标域名判定(内网直连/外网走代理)
            InetSocketAddress proxy = com.nora.common.http.ProxySupport
                    .addressFor(proxyProperties, embeddingProperties.baseUrl());
            if (proxy != null) {
                builder.proxy(java.net.ProxySelector.of(proxy));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(embeddingProperties.baseUrl()) + "/rerank"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + embeddingProperties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = builder.build().send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return new RerankOutcome(false, candidates,
                        "rerank HTTP " + response.statusCode());
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) {
                return new RerankOutcome(false, candidates, "rerank 响应无 results");
            }
            List<RetrievalResult> reordered = new ArrayList<>(candidates.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                RetrievalResult r = candidates.get(index);
                double rerankScore = item.path("relevance_score").asDouble(r.score());
                // 重排分写入 score(展示与排序用),原融合序由 results 顺序表达;
                // 通道细分字段保留(命中原样可查)
                reordered.add(new RetrievalResult(
                        r.docId(), r.docName(), r.chunkIndex(), r.chunkId(),
                        rerankScore, r.vectorScore(), r.keywordScore(),
                        r.matchChannel() == null ? null : r.matchChannel(),
                        r.snippet(), r.content(), r.source(),
                        r.parentIndex(), r.parentContent()));
            }
            if (reordered.size() != candidates.size()) {
                // 响应不完整:保守保留原序(不部分重排)
                return new RerankOutcome(false, candidates, "rerank 响应条目不完整");
            }
            return new RerankOutcome(true, reordered, null);
        } catch (Exception e) {
            log.warn("rerank failed (keeping fused order): {}", e.getMessage());
            return new RerankOutcome(false, candidates, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
