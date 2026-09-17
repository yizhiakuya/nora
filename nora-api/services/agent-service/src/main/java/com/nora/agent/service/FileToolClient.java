package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

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
     * Lists workbench files (id, name, size, indexed flag, folder).
     *
     * <p>输出带文件夹归属(2026-09-17):用户在文件中心整理的目录结构
     * 对 AI 可见——「项目资料文件夹里有什么」这类问题能直接回答;
     * 文件列表按文件夹分组渲染(根目录文件在最前)。
     *
     * @return one line per file, grouped by folder
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
            // 文件夹名映射(拿不到就只显示 id——不阻断列表)
            Map<Long, String> folderNames = new java.util.HashMap<>();
            try {
                Envelope<JsonNode> folders = restClient.get()
                        .uri("/api/files/folders")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {
                        });
                if (folders != null && folders.data() != null && folders.data().isArray()) {
                    for (JsonNode f : folders.data()) {
                        folderNames.put(f.path("id").asLong(), f.path("name").asText("?"));
                    }
                }
            } catch (Exception e) {
                log.debug("folder list unavailable for read_file: {}", e.getMessage());
            }
            StringBuilder sb = new StringBuilder();
            // 根目录文件先列(无 folderId)
            for (JsonNode n : envelope.data()) {
                if (n.path("folderId").isNull() || n.path("folderId").isMissingNode()) {
                    sb.append(renderFileLine(n, null));
                }
            }
            // 再按文件夹分组
            for (Map.Entry<Long, String> e : folderNames.entrySet()) {
                StringBuilder group = new StringBuilder();
                for (JsonNode n : envelope.data()) {
                    if (!n.path("folderId").isMissingNode() && n.path("folderId").asLong(-1) == e.getKey()) {
                        group.append(renderFileLine(n, e.getValue()));
                    }
                }
                if (group.length() > 0) {
                    sb.append("📁 文件夹「").append(e.getValue()).append("」:\n").append(group);
                }
            }
            // 兜底:文件夹列表拉取失败时,带 folderId 的文件会在上面的分组循环里
            // 全部漏掉(用户整理进文件夹的文件对 AI 不可见)。这里把它们以
            // 「文件夹 #id」形态补上,保证 read_file list 永远覆盖全部文件。
            if (folderNames.isEmpty()) {
                StringBuilder orphan = new StringBuilder();
                for (JsonNode n : envelope.data()) {
                    if (!n.path("folderId").isNull() && !n.path("folderId").isMissingNode()) {
                        orphan.append(renderFileLine(n, "文件夹#" + n.path("folderId").asLong()));
                    }
                }
                if (orphan.length() > 0) {
                    sb.append("📁 文件夹(名称暂不可用,按 id 展示):\n").append(orphan);
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("file list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 单行文件渲染(id | 名称 | 大小 | 索引状态)。 */
    private static String renderFileLine(JsonNode n, String folderName) {
        return "id=" + n.path("id").asLong()
                + " | " + n.path("name").asText("?")
                + " | " + formatSize(n.path("sizeBytes").asLong(0))
                + " | " + (n.path("indexed").asBoolean() ? "indexed" : "not-indexed")
                + (folderName == null ? "" : " | 文件夹:" + folderName)
                + '\n';
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
        PreviewInfo info = previewInfo(fileId);
        if (info.failed()) {
            return "ERROR: " + info.error();
        }
        if (!info.hasText()) {
            return "文件 " + info.name() + " 没有可提取的文本内容(可能是二进制/图片)";
        }
        return renderPreview(info);
    }

    /**
     * 预览元数据:文本提取结果 + 文件名;失败时 {@code error} 非空。
     * 调用方据此判断是否属于「文本为空」的二进制/图片,决定是否走图像通道。
     *
     * @param name  文件名({@code null}=请求失败)
     * @param text  提取出的文本(可为空串)
     * @param error 失败原因({@code null}=成功)
     */
    public record PreviewInfo(String name, String text, String error) {
        public boolean failed() {
            return error != null;
        }

        public boolean hasText() {
            return error == null && text != null && !text.isBlank();
        }
    }

    /** 取预览元数据(不渲染;失败时 error 非空)。 */
    public PreviewInfo previewInfo(long fileId) {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/files/{id}/preview", fileId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return new PreviewInfo(null, null, envelope == null ? "empty response" : envelope.message());
            }
            return new PreviewInfo(
                    envelope.data().path("name").asText("?"),
                    envelope.data().path("textContent").asText(""),
                    null);
        } catch (Exception e) {
            log.warn("file preview failed for {}: {}", fileId, e.getMessage());
            return new PreviewInfo(null, null, e.getMessage() + "(先用 action=list 查看可用文件 id)");
        }
    }

    /** 渲染预览文本(头部 + 尾部截断,12K 预算)。 */
    public String renderPreview(PreviewInfo info) {
        String text = info.text();
        StringBuilder sb = new StringBuilder("=== ").append(info.name()).append(" ===\n");
        if (text.length() <= 12_000) {
            sb.append(text);
        } else {
            sb.append(text, 0, 10_000)
                    .append("\n…[中间省略 ").append(text.length() - 12_000).append(" 字符]…\n")
                    .append(text.substring(text.length() - 2_000));
        }
        return sb.toString();
    }

    /** 原始字节 + MIME(图片走图像通道用)。 */
    public record RawFile(String mimeType, byte[] bytes) {
    }

    /**
     * 单个文件的元数据(id/name/mimeType/sizeBytes)。
     *
     * @param id file id
     * @return 元数据;失败 {@code null}
     */
    public JsonNode meta(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/files?ids={id}", id)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null
                    || !envelope.data().isArray() || envelope.data().isEmpty()) {
                return null;
            }
            return envelope.data().get(0);
        } catch (Exception e) {
            log.warn("file meta failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 读取文件原始字节(file-service {@code /{id}/raw})。
     *
     * <p>文本提取为空(二进制/图片)时调用:拿到原始字节后按文件头识别图片,
     * 作为图像附件喂给视觉模型。失败返回 {@code null},由调用方回退文本提示。
     *
     * @param fileId file id
     * @return 字节与 MIME;失败 {@code null}
     */
    public RawFile raw(long fileId) {
        try {
            ResponseEntity<byte[]> resp = restClient.get()
                    .uri("/api/files/{id}/raw", fileId)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                return null;
            }
            MediaType contentType = resp.getHeaders().getContentType();
            return new RawFile(contentType == null ? "application/octet-stream" : contentType.toString(), body);
        } catch (Exception e) {
            log.warn("file raw failed for {}: {}", fileId, e.getMessage());
            return null;
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
