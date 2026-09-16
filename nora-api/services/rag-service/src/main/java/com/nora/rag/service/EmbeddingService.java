package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.common.redis.NoraRedis;
import com.nora.rag.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Embedding facade over the LangChain4j OpenAI-compatible client, pointed at
 * Jina AI via {@code nora.embedding.base-url}.
 *
 * <p>The underlying {@link EmbeddingModel} is created lazily per call site so
 * the application starts and serves non-embedding endpoints (docs list, stats)
 * even when no API key is configured.
 *
 * <p><b>Redis cache (optional):</b> identical texts (re-indexing, repeated
 * search queries) hit a content-addressed cache instead of paying another
 * provider round-trip. Cache misses and Redis outages fall through to the
 * provider; the cache never changes results, only latency and cost.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Content-addressed cache key prefix; the hash covers model+dimensions+text. */
    static final String CACHE_PREFIX = "nora:embed:";
    /** TTL: embeddings are deterministic per model version, 7 days is safe headroom. */
    static final long CACHE_TTL_SECONDS = 7 * 24 * 3600;

    private final EmbeddingProperties properties;
    private final com.nora.common.http.ProxyProperties proxyProperties;
    private final NoraRedis redis;

    public EmbeddingService(EmbeddingProperties properties,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.nora.common.http.ProxyProperties proxyProperties) {
        this(properties, proxyProperties, null);
    }

    @Autowired
    public EmbeddingService(EmbeddingProperties properties,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.nora.common.http.ProxyProperties proxyProperties,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            NoraRedis redis) {
        this.properties = properties;
        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
        this.redis = redis;
    }

    /**
     * Embeds a single text.
     *
     * @throws BusinessException "embedding not configured" when no API key is set
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
     * Embeds a batch of texts in one provider call.
     *
     * @throws BusinessException "embedding not configured" when no API key is set
     */
    public List<float[]> embedAll(List<String> texts) {
        if (!properties.configured()) {
            throw new BusinessException(500, "embedding not configured");
        }
        // 缓存命中项直接取;未命中项合并成一次 provider 调用(保持原批量语义)
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
            List<String> missTexts = misses.stream().map(texts::get).toList();
            List<Embedding> embeddings = model().embedAll(
                    missTexts.stream().map(dev.langchain4j.data.segment.TextSegment::from).toList()
            ).content();
            for (int j = 0; j < misses.size(); j++) {
                float[] vector = embeddings.get(j).vector();
                out.set(misses.get(j), vector);
                cachePut(missTexts.get(j), vector);
            }
        }
        return out;
    }

    /** Reads one vector from the Redis cache; empty when disabled, unreachable or missing. */
    private Optional<float[]> cacheGet(String text) {
        if (redis == null) {
            return Optional.empty();
        }
        return redis.call(commands -> {
            String cached = commands.get(cacheKey(text));
            return cached == null ? null : decodeVector(cached);
        });
    }

    /** Writes one vector to the Redis cache (best-effort, TTL-bounded). */
    private void cachePut(String text, float[] vector) {
        if (redis == null) {
            return;
        }
        redis.call(commands -> {
            commands.setex(cacheKey(text), CACHE_TTL_SECONDS, encodeVector(vector));
            return null;
        });
    }

    /** SHA-256 over model + dimensions + text: model swaps never serve stale vectors. */
    private String cacheKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((properties.model() + ":" + properties.dimensions() + ":").getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return CACHE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            // 摘要失败(理论上不可能):退化为不缓存
            return CACHE_PREFIX + "unavailable";
        }
    }

    /** float[] ↔ Base64(fixed 4-byte LE)紧凑编码,1024 维约 5.5KB。 */
    private static String encodeVector(float[] vector) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decodeVector(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
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
