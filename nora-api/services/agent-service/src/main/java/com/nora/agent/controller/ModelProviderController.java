package com.nora.agent.controller;

import com.nora.agent.service.ModelProviderService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Model provider CRUD + connectivity test ({@code /api/models/providers}),
 * per the initiation doc REST contract. All responses use the ApiResponse envelope.
 */
@RestController
@RequestMapping("/api/models/providers")
public class ModelProviderController {

    private final ModelProviderService providerService;
    private final RestClient testClient;

    public ModelProviderController(ModelProviderService providerService) {
        this.providerService = providerService;
        this.testClient = RestClient.builder()
                // no baseUrl: each provider has its own endpoint
                .build();
    }

    /** Lists all providers (keys masked). */
    @GetMapping
    public ApiResponse<List<ModelProviderService.ProviderView>> list() {
        return ApiResponse.ok(providerService.list());
    }

    /** Creates a provider. */
    @PostMapping
    public ApiResponse<ModelProviderService.ProviderView> create(@RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        String protocol = request.protocol() == null ? "openai" : request.protocol();
        return ApiResponse.ok(providerService.create(
                request.name().trim(), protocol, request.endpoint(),
                request.apiKey(), request.models()));
    }

    /** Updates name/enabled/models; other fields keep their stored values. */
    @PutMapping("/{id}")
    public ApiResponse<ModelProviderService.ProviderView> update(@PathVariable long id,
                                                                 @RequestBody UpdateRequest request) {
        ModelProviderService.ProviderView updated = providerService.update(
                id, request.name(), request.enabled(), request.models());
        if (updated == null) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        return ApiResponse.ok(updated);
    }

    /** Deletes a provider. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!providerService.delete(id)) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        return ApiResponse.ok();
    }

    /**
     * Connectivity test: GETs {@code {endpoint}/models} with the stored key
     * (OpenAI-compatible /v1/models). Marks the provider ok/fail.
     */
    @PostMapping("/{id}/test")
    public ApiResponse<TestResult> test(@PathVariable long id) {
        ModelProviderService.StoredCredentials credentials = providerService.credentials(id);
        if (credentials == null) {
            throw new BusinessException(404, "provider not found: " + id);
        }
        String base = credentials.endpoint() == null ? "" : credentials.endpoint().replaceAll("/+$", "");
        try {
            RestClient.RequestHeadersSpec<?> spec = testClient.get()
                    .uri(base + "/models")
                    .accept(MediaType.APPLICATION_JSON);
            if (credentials.apiKey() != null && !credentials.apiKey().isBlank()) {
                spec = ((RestClient.RequestHeadersSpec<?>) spec)
                        .header("Authorization", "Bearer " + credentials.apiKey());
            }
            spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new BusinessException(502, "endpoint returned " + res.getStatusCode() + ": "
                                + body.substring(0, Math.min(body.length(), 200)));
                    })
                    .toEntity(String.class);
            providerService.markStatus(id, "ok");
            return ApiResponse.ok(new TestResult("ok", null));
        } catch (BusinessException e) {
            providerService.markStatus(id, "fail");
            throw e;
        } catch (Exception e) {
            providerService.markStatus(id, "fail");
            throw new BusinessException(502, "connection failed: " + e.getMessage());
        }
    }

    /** POST body for provider creation. */
    public record CreateRequest(
            String name, String protocol, String endpoint, String apiKey, List<String> models) {
    }

    /** PUT body for provider updates (partial). */
    public record UpdateRequest(String name, Boolean enabled, List<String> models) {
    }

    /** POST /test response. */
    public record TestResult(String status, String error) {
    }
}
