package com.nora.common.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 服务间调用的信封感知错误处理器(异常处理系统 2026-09-12)。
 *
 * <p>背景:统一异常处理后,下游服务的业务错误返回非 2xx + 结构化信封
 * {@code {code,message,category,hint,retryable,traceId}}。RestClient 默认
 * 对非 2xx 抛 {@code RestClientResponseException},其 message 是难读的
 * Spring 异常串;调用方 catch 后回填模型/日志的文本会退化成噪音。
 *
 * <p>此处理器把响应信封的 message/hint 提取为干净的异常 message,
 * 并保留分类等字段(category 打进 message 尾部,供排障;业务侧主要读 message)。
 *
 * <p>用法(所有服务间 RestClient 统一配置):
 * <pre>{@code
 * RestClient.builder()
 *     .baseUrl(baseUrl)
 *     .defaultStatusHandler(EnvelopeErrorHandler.create())
 *     .build();
 * }</pre>
 */
public final class EnvelopeErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopeErrorHandler() {
    }

    /** 创建处理器(无状态,可跨 RestClient 复用)。 */
    public static RestClient.ResponseSpec.ErrorHandler create() {
        return EnvelopeErrorHandler::handle;
    }

    private static void handle(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body;
        try {
            body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "";
        }
        String message = extractMessage(body);
        if (message == null) {
            message = "HTTP " + status + (body.isBlank() ? "" : ": " + abbreviate(body));
        }
        log.debug("downstream {} {} -> {}: {}", request.getMethod(), request.getURI(), status, message);
        throw new DownstreamException(status, message, request.getURI().getPath());
    }

    /** 从信封提取 message;非信封/解析失败返回 null。 */
    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.hasNonNull("message")) {
                String message = root.path("message").asText("");
                String category = root.path("category").asText("");
                String errorCode = root.path("errorCode").asText("");
                String hint = root.path("hint").asText("");
                StringBuilder sb = new StringBuilder(message);
                if (!hint.isBlank()) {
                    sb.append("（建议: ").append(hint).append(")");
                }
                if (!category.isBlank() || !errorCode.isBlank()) {
                    sb.append(" [").append(category.isBlank() ? "-" : category);
                    if (!errorCode.isBlank()) {
                        sb.append('/').append(errorCode);
                    }
                    sb.append(']');
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // 非 JSON(网关 HTML 等):交给调用方的兜底文案
        }
        return null;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /**
     * 下游服务错误(信封 message 已提取):调用方 catch 后可直接把
     * {@code getMessage()} 回填模型/日志,无需再解析信封。
     */
    public static class DownstreamException extends RuntimeException {
        private final int status;
        private final String path;

        public DownstreamException(int status, String message, String path) {
            super(message);
            this.status = status;
            this.path = path;
        }

        public int getStatus() {
            return status;
        }

        public String getPath() {
            return path;
        }
    }
}
