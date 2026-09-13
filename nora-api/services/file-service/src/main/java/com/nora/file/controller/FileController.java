package com.nora.file.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.nora.common.response.ApiResponse;
import com.nora.file.api.FileItem;
import com.nora.file.api.FilePreview;
import com.nora.file.client.RagIndexClient;
import com.nora.file.service.FileStorageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for file upload, listing, deletion, preview, and indexing.
 * All responses use the unified {@link ApiResponse} envelope (camelCase JSON).
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;
    private final RagIndexClient ragIndexClient;

    public FileController(FileStorageService fileStorageService, RagIndexClient ragIndexClient) {
        this.fileStorageService = fileStorageService;
        this.ragIndexClient = ragIndexClient;
    }

    /**
     * Uploads a file (multipart), stores it, and persists its metadata.
     *
     * @param file multipart file part
     * @return the persisted file item
     */
    @PostMapping("/upload")
    public ApiResponse<FileItem> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(fileStorageService.store(file));
    }

    /**
     * Lists files. When {@code ids} is present, only those ids are returned
     * (also used to poll indexing state); otherwise all files are returned.
     *
     * @param ids optional comma-separated id filter
     * @return matching file items
     */
    @GetMapping
    public ApiResponse<List<FileItem>> list(@RequestParam(value = "ids", required = false) String ids) {
        return ApiResponse.ok(fileStorageService.list(parseIds(ids)));
    }

    /**
     * Deletes files by ids and removes the backing files from disk.
     *
     * @param ids comma-separated id list (required)
     * @return {@code ok(null)}
     */
    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam("ids") String ids) {
        fileStorageService.delete(parseIds(ids));
        return ApiResponse.ok();
    }

    /**
     * Returns the extracted plain-text preview of a file.
     *
     * @param id file id
     * @return preview with type and text content
     */
    @GetMapping("/{id}/preview")
    public ApiResponse<FilePreview> preview(@PathVariable Long id) {
        return ApiResponse.ok(fileStorageService.preview(id));
    }

    /**
     * 返回文件原始字节(图片/PDF 等二进制预览用;前端 &lt;img src&gt; 直接引用)。
     * Content-Type 用文件真实 mime,浏览器按图片渲染。
     *
     * @param id file id
     * @return raw bytes with the stored mime type
     */
    @GetMapping("/{id}/raw")
    public org.springframework.http.ResponseEntity<byte[]> raw(@PathVariable Long id) {
        com.nora.file.api.FileItem item = fileStorageService.getById(id);
        byte[] body = fileStorageService.raw(id);
        String mime = item.mimeType() == null || item.mimeType().isBlank()
                ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
                : item.mimeType();
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", mime)
                .header("Cache-Control", "private, max-age=300")
                .body(body);
    }

    /**
     * Triggers rag-service indexing asynchronously (fire-and-forget) and
     * immediately returns the current file item (still {@code indexed=false}).
     *
     * @param id file id
     * @return the current file item
     */
    @PostMapping("/{id}/index")
    public ApiResponse<FileItem> index(@PathVariable Long id) {
        FileItem item = fileStorageService.getById(id);
        ragIndexClient.triggerIndexAsync(item);
        return ApiResponse.ok(item);
    }

    /**
     * Callback endpoint for rag-service: marks the file as indexed.
     *
     * @param id file id
     * @return the updated file item with {@code indexed=true}
     */
    @PostMapping("/{id}/indexed")
    public ApiResponse<FileItem> indexed(@PathVariable Long id) {
        return ApiResponse.ok(fileStorageService.markIndexed(id));
    }

    /** Parses a comma-separated id list; blank/absent values yield an empty list. */
    private List<Long> parseIds(String ids) {
        return Optional.ofNullable(ids)
                .filter(s -> !s.isBlank())
                .stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}
