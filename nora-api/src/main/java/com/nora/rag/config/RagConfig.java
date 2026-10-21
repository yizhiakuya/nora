package com.nora.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.nora.common.http.ProxyProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmbeddingProperties.class, ProxyProperties.class, RetrievalProperties.class})
public class RagConfig {}
