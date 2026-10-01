package com.nora.agent.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.nora.agent.service.ModelProviderService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

/**
 * 模型 provider CRUD + 连通测试({@code /api/models/providers}),按立项文档
 * REST 契约。全部响应使用 ApiResponse 信封。
 */
@RestController
@RequestMapping("/api/models/providers")
public class ModelProviderController {

    private final ModelProviderService providerService;
    public ModelProviderController(ModelProviderService providerService) {
        this.providerService = providerService;
    }

    /** 列出全部 provider(key 脱敏)。 */
    @GetMapping
    public ApiResponse<List<ModelProviderService.ProviderView>> list() {
        return ApiResponse.ok(providerService.list());
    }

    /** 创建 provider。 */
    @PostMapping
    public ApiResponse<ModelProviderService.ProviderView> create(@RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        String protocol = request.protocol() == null ? "openai" : request.protocol();
        return ApiResponse.ok(providerService.create(
                request.name().trim(), protocol, request.endpoint(),
                request.apiKey(), request.models(), request.modelSettings()));
    }

    /** 更新 name/enabled/models/protocol/endpoint/apiKey;null 字段保持已存值。 */
    @PutMapping("/{id}")
    public ApiResponse<ModelProviderService.ProviderView> update(@PathVariable long id,
                                                                 @RequestBody UpdateRequest request) {
        ModelProviderService.ProviderView updated = providerService.update(
                id, request.name(), request.protocol(), request.endpoint(), request.apiKey(),
                request.enabled(), request.models(), request.modelSettings());
        if (updated == null) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        return ApiResponse.ok(updated);
    }

    /** 删除 provider。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!providerService.delete(id)) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        return ApiResponse.ok();
    }

    /**
     * 连通测试:用已存 key GET {@code {endpoint}/models}(OpenAI 兼容 /v1/models)。
     * 标记 provider ok/fail;返回上游发现的模型清单供前端选择——**不落库覆盖**,
     * 已选模型以保存时的勾选为唯一权威(2026-10-01 实测修复:此前测试会把
     * 用户只勾选的 1 个模型重置成上游全部 41 个)。
     */
    @PostMapping("/{id}/test")
    public ApiResponse<TestResult> test(@PathVariable long id) {
        ModelProviderService.StoredCredentials credentials = providerService.credentials(id);
        if (credentials == null) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        try {
            TestResult result = discoverModels(credentials.endpoint(), credentials.apiKey());
            providerService.markStatus(id, "ok");
            return ApiResponse.ok(result);
        } catch (BusinessException e) {
            providerService.markStatus(id, "fail");
            throw e;
        }
    }

    /** 探测草稿配置,不写库;编辑时仅密钥留空才读取已存密钥。 */
    @PostMapping("/probe")
    public ApiResponse<TestResult> probe(@RequestBody ProbeRequest request) {
        String apiKey = request.apiKey();
        if ((apiKey == null || apiKey.isBlank()) && request.providerId() != null) {
            ModelProviderService.StoredCredentials stored = providerService.credentials(request.providerId());
            if (stored == null) throw new BusinessException(404, "provider not found");
            apiKey = stored.apiKey();
        }
        return ApiResponse.ok(discoverModels(request.endpoint(), apiKey));
    }

    private TestResult discoverModels(String endpoint, String apiKey) {
        if (endpoint == null || endpoint.isBlank()) throw new BusinessException(400, "endpoint is required");
        java.net.URI uri;
        try {
            uri = java.net.URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "invalid endpoint URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new BusinessException(400, "endpoint must be an http(s) URL without credentials");
        }
        String base = endpoint.replaceAll("/+$", "");
        try {
            // 外网端点走配置的出站代理(内网/直连目标不受影响)
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder
                    .addressFor(base);
            if (proxyAddr != null) {
                factory.setProxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, proxyAddr));
            }
            RestClient perCallClient = RestClient.builder().requestFactory(factory).build();
            RestClient.RequestHeadersSpec<?> spec = perCallClient.get()
                    .uri(base + "/models")
                    .accept(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                spec = ((RestClient.RequestHeadersSpec<?>) spec)
                        .header("Authorization", "Bearer " + apiKey);
            }
            String body = spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new BusinessException(502, "endpoint returned " + res.getStatusCode() + ": "
                                + errBody.substring(0, Math.min(errBody.length(), 200)));
                    })
                    .toEntity(String.class)
                    .getBody();
            List<String> models = parseModelIds(body);
            return new TestResult("ok", null, models.size(), models);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "connection failed: " + e.getMessage());
        }
    }

    /** 从 OpenAI 兼容的 /v1/models 响应提取模型 id。 */
    private static List<String> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            List<String> ids = new java.util.ArrayList<>();
            if (data.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : data) {
                    String id = item.path("id").asText(null);
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** provider 创建的 POST 请求体。 */
    public record CreateRequest(
            String name, String protocol, String endpoint, String apiKey, List<String> models,
            ModelProviderService.ModelSettings modelSettings) {
    }

    /** provider 更新的 PUT 请求体(部分;null/空白 apiKey 保持已存 key)。 */
    public record UpdateRequest(String name, String protocol, String endpoint, String apiKey,
                                Boolean enabled, List<String> models,
                                ModelProviderService.ModelSettings modelSettings) {
    }

    public record ProbeRequest(String endpoint, String apiKey, Long providerId) {
    }

    /** POST /test 响应。models = 上游发现的 id(供选择器 UI)。 */
    public record TestResult(String status, String error, Integer modelCount, List<String> models) {
    }
}
