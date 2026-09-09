package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reads workbench files via file-service ({@code GET /api/files},
 * {@code GET /api/files/{id}/preview}) for the agent's {@code read_file}
 * tool. Preview text comes pre-extracted (Tika); this client only bounds
 * and renders it.
 */
@Service
public class FileToolClient {

    private static final Logger log = LoggerFactory.getLogger(FileToolClient.class);

    private final RestClient restClient;

    public FileToolClient(RestClient fileServiceRestClient) {
        this.restClient = fileServiceRestClient;
    }

    /**
     * Lists workbench files (id, name, size, indexed flag).
     *
     * @return one "id | name | size | indexed" line per file
     */
    public String list() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/files")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(工作台还没有上传任何文件)";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                sb.append("id=").append(n.path("id").asLong())
                        .append(" | ").append(n.path("name").asText("?"))
                        .append(" | ").append(n.path("sizeBytes").asLong(0) / 1024).append("KB")
                        .append(" | ").append(n.path("indexed").asBoolean() ? "indexed" : "not-indexed")
                        .append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("file list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Reads the extracted text content of a file (Tika preview). Bounded to
     * 12K chars with head+tail so the model sees both the beginning (docs)
     * and the end (conclusions/logs).
     *
     * @param fileId file id from {@link #list()}
     * @return header + bounded text, or an error line
     */
    public String preview(long fileId) {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/files/{id}/preview", fileId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            String name = envelope.data().path("name").asText("?");
            String text = envelope.data().path("textContent").asText("");
            if (text.isBlank()) {
                return "文件 " + name + " 没有可提取的文本内容(可能是二进制/图片)";
            }
            StringBuilder sb = new StringBuilder("=== ").append(name).append(" ===\n");
            if (text.length() <= 12_000) {
                sb.append(text);
            } else {
                sb.append(text, 0, 10_000)
                        .append("\n…[中间省略 ").append(text.length() - 12_000).append(" 字符]…\n")
                        .append(text.substring(text.length() - 2_000));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("file preview failed for {}: {}", fileId, e.getMessage());
            return "ERROR: " + e.getMessage() + "(先用 action=list 查看可用文件 id)";
        }
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
