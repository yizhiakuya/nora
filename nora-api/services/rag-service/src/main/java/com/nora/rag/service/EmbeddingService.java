package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding facade over the LangChain4j OpenAI-compatible client, pointed at
 * Jina AI via {@code nora.embedding.base-url}.
 *
 * <p>The underlying {@link EmbeddingModel} is created lazily per call site so
 * the application starts and serves non-embedding endpoints (docs list, stats)
 * even when no API key is configured.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProperties properties;
    private final com.nora.common.http.ProxyProperties proxyProperties;

    public EmbeddingService(EmbeddingProperties properties,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.nora.common.http.ProxyProperties proxyProperties) {
        this.properties = properties;
        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
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
        Embedding embedding = model().embed(text).content();
        return embedding.vector();
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
        List<Embedding> embeddings = model().embedAll(
                texts.stream().map(dev.langchain4j.data.segment.TextSegment::from).toList()
        ).content();
        return embeddings.stream().map(Embedding::vector).collect(Collectors.toList());
    }

    /** Configured model name, surfaced in index stats. */
    public String modelName() {
        return properties.model();
    }

    /** Configured vector dimension, surfaced in index stats. */
    public int dimensions() {
        return properties.dimensions();
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
