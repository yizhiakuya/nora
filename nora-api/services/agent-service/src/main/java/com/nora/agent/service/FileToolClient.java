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
                        .append(" | ").append(formatSize(n.path("sizeBytes").asLong(0)))
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

    /**
     * 上传一个文件到工作台「文件」(多部件 POST /api/files/upload)。
     *
     * <p>用于把远程资源(如相册图片)存成工作台文件,
     * 用户在「文件」页可直接查看/预览/删除/建索引。
     *
     * @param filename 存储用的文件名(带扩展名,决定 MIME 推断)
     * @param bytes    文件内容
     * @return 成功:“已存入工作台文件 id=X | name | sizeKB”;失败:ERROR 行
     */
    public String upload(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "ERROR: 上传内容为空";
        }
        String name = (filename == null || filename.isBlank()) ? "import.bin" : filename;
        try {
            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return name;
                        }
                    };
            org.springframework.util.LinkedMultiValueMap<String, Object> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            form.add("file", resource);
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/files/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            long id = envelope.data().path("id").asLong();
            long size = envelope.data().path("sizeBytes").asLong(bytes.length);
            return "已存入工作台文件 id=" + id + " | " + name + " | " + formatSize(size);
        } catch (Exception e) {
            log.warn("file upload failed for {}: {}", name, e.getMessage());
            return "ERROR: 上传失败: " + e.getMessage();
        }
    }

    /**
     * 人类可读的文件大小:小文件显示 B/KB(带一位小数),大文件显示 MB。
     * 用整数除法会把手里的 164B 显示成 "0KB",让 Agent 误以为落盘为空
     * (实测踩过:模型据此提示“可疑:文件为 0KB”)。
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
