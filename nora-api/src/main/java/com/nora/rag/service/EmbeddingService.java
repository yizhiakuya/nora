package com.nora.rag.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

/**
 * LangChain4j OpenAI 兼容客户端上的嵌入门面,经
 * {@code nora.embedding.base-url} 指向 Jina AI。
 *
 * <p>底层 {@link EmbeddingModel} 按调用点懒创建,让应用在没配 API key 时
 * 也能启动并服务非嵌入端点(文档列表、统计)。
 *
 * <p>嵌入缓存使用有界进程缓存，重启只影响命中率。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProperties properties;
    private final com.nora.common.http.ProxyProperties proxyProperties;
    private final Cache<String, float[]> cache = Caffeine.newBuilder()
            .maximumWeight(32 * 1024 * 1024)
            .weigher((String key, float[] vector) -> key.length() * 2 + vector.length * Float.BYTES)
            .expireAfterWrite(java.time.Duration.ofDays(7)).build();

    public EmbeddingService(EmbeddingProperties properties, com.nora.common.http.ProxyProperties proxyProperties) {
        this.properties = properties;
        this.proxyProperties = proxyProperties;
    }

    /**
     * 嵌入单条文本。
     *
     * @throws BusinessException 未配置 API key 时 "embedding not configured"
     */
    public float[] embed(String text) {
        if (!properties.configured()) {
            throw new BusinessException(500, "embedding not configured");
        }
        Optional<float[]> cached = cacheGet(text);
        if (cached.isPresent()) {
            return cached.get();
        }
        Embedding embedding = model().embed(text).content();
        float[] vector = embedding.vector();
        cachePut(text, vector);
        return vector;
    }

    /**
     * 一次 provider 调用嵌入一批文本。
     *
     * <p><b>自动分批(2026-09-22,阶段 A):</b>大批量(如 50 万字符文档 ≈ 278 块
     * ≈ 27.8 万 tokens)一次性提交会撞 provider 的速率限制(实测 Jina
     * 10 万 tokens/分钟 → 502 "Token rate limit exceeded")。按
     * {@link #EMBED_BATCH_SIZE} 块分批提交,批间保持缓存语义。
     *
     * @throws BusinessException 未配置 API key 时 "embedding not configured"
     */
    public List<float[]> embedAll(List<String> texts) {
        if (!properties.configured()) {
            throw new BusinessException(500, "embedding not configured");
        }
        // 缓存命中项直接取;未命中项合并成批量调用(保持原批量语义)
        List<float[]> out = new ArrayList<>(java.util.Collections.nCopies(texts.size(), null));
        List<Integer> misses = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            Optional<float[]> cached = cacheGet(texts.get(i));
            if (cached.isPresent()) {
                out.set(i, cached.get());
            } else {
                misses.add(i);
            }
        }
        if (!misses.isEmpty()) {
            // 分批:每批最多 EMBED_BATCH_SIZE 块(大文档不再单次烧穿速率配额);
            // 撞限速时退避重试(见 embedBatchWithRetry)
            for (int from = 0; from < misses.size(); from += EMBED_BATCH_SIZE) {
                int to = Math.min(from + EMBED_BATCH_SIZE, misses.size());
                List<Integer> batch = misses.subList(from, to);
                List<String> batchTexts = batch.stream().map(texts::get).toList();
                List<Embedding> embeddings = embedBatchWithRetry(batchTexts);
                for (int j = 0; j < batch.size(); j++) {
                    float[] vector = embeddings.get(j).vector();
                    out.set(batch.get(j), vector);
                    cachePut(batchTexts.get(j), vector);
                }
            }
        }
        return out;
    }

    /**
     * 单次 provider 调用的最大块数。
     *
     * <p>取值依据:Jina 速率限制 10 万 tokens/分钟;块按 ~1000 tokens 估,
     * 40 块 ≈ 4 万 tokens 留出安全余量(多文档并发索引时仍不叠加超限)。
     */
    static final int EMBED_BATCH_SIZE = 40;

    /** 撞速率限制时的退避重试次数。 */
    static final int RATE_LIMIT_MAX_RETRIES = 3;

    /**
     * 单批嵌入,撞速率限制时退避重试(2026-09-22,阶段 A)。
     *
     * <p>限速是**每分钟滚动窗口**——大文档(如 50 万字符 ≈ 278 块)即使分批,
     * 总量也可能超出单分钟配额;不退避的话索引直接失败,用户要手等 1 分钟
     * 再重试。这里识别 provider 的限速错误(消息含 rate limit / 429),
     * 按 30s 递增退避(30/60/90s),最多 {@link #RATE_LIMIT_MAX_RETRIES} 次;
     * 其它错误立即上抛(不掩盖真实故障)。
     */
    private List<Embedding> embedBatchWithRetry(List<String> batchTexts) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= RATE_LIMIT_MAX_RETRIES; attempt++) {
            try {
                return model().embedAll(
                        batchTexts.stream().map(dev.langchain4j.data.segment.TextSegment::from).toList()
                ).content();
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || attempt == RATE_LIMIT_MAX_RETRIES) {
                    throw e;
                }
                last = e;
                long waitMs = 30_000L * (attempt + 1);
                log.warn("embedding rate limited, backing off {}s before retry {}/{}: {}",
                        waitMs / 1000, attempt + 1, RATE_LIMIT_MAX_RETRIES, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IllegalStateException("embedding retry exhausted") : last;
    }

    /** 是否 provider 速率限制(消息启发式:Jina/OpenAI 兼容均含 rate limit / 429)。 */
    private static boolean isRateLimited(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String message = cur.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("rate limit") || lower.contains("rate_limit") || lower.contains("429")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private Optional<float[]> cacheGet(String text) {
        return Optional.ofNullable(cache.getIfPresent(cacheKey(text))).map(float[]::clone);
    }

    private void cachePut(String text, float[] vector) {
        cache.put(cacheKey(text), vector.clone());
    }

    private String cacheKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((properties.baseUrl() + ":" + properties.model() + ":" + properties.dimensions() + ":")
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private EmbeddingModel model() {
        dev.langchain4j.http.client.jdk.JdkHttpClientBuilder httpClientBuilder =
                new dev.langchain4j.http.client.jdk.JdkHttpClientBuilder();
        // 出站代理:Jina 等外网 embedding 提供方需要时挂到底层 JDK HttpClient
        java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySupport
                .addressFor(proxyProperties, properties.baseUrl());
        if (proxyAddr != null) {
            httpClientBuilder.httpClientBuilder(java.net.http.HttpClient.newBuilder()
                    .proxy(java.net.ProxySelector.of(proxyAddr)));
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.model())
                .dimensions(properties.dimensions())
                .httpClientBuilder(httpClientBuilder)
                .build();
    }
}
