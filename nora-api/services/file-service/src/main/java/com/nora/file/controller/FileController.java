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
     * 返回文件原始字节(图片/视频/PDF 等二进制预览用;前端 &lt;img&gt;/&lt;video&gt; 直接引用)。
     *
     * <p>2026-09-17 起改为**流式 + HTTP Range 支持**:
     * 视频播放器拖进度条需要 Range 请求;此前 readAllBytes 全量进内存,
     * 几百 MB 的视频会 OOM 且无法 seek。单区间 Range 返回 206 +
     * Content-Range(多区间合并为整体返回,视频 seek 只发单区间)。
     *
     * @param id    file id
     * @param range 可选 Range 头(形如 {@code bytes=0-1023})
     * @return streaming resource (200) or partial region (206)
     */
    @GetMapping("/{id}/raw")
    public org.springframework.http.ResponseEntity<?> raw(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Range", required = false) String range) {
        com.nora.file.api.FileItem item = fileStorageService.getById(id);
        java.nio.file.Path path = fileStorageService.resolveFile(id);
        String mime = item.mimeType() == null || item.mimeType().isBlank()
                ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
                : item.mimeType();
        org.springframework.core.io.FileSystemResource resource =
                new org.springframework.core.io.FileSystemResource(path);

        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            try {
                org.springframework.http.HttpRange r =
                        org.springframework.http.HttpRange.parseRanges(range).get(0);
                long length = resource.contentLength();
                long start = r.getRangeStart(length);
                long end = Math.min(r.getRangeEnd(length), length - 1);
                if (start <= end) {
                    long regionLength = end - start + 1;
                    return org.springframework.http.ResponseEntity
                            .status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                            .header("Content-Type", mime)
                            .header("Accept-Ranges", "bytes")
                            .header("Content-Range", "bytes " + start + "-" + end + "/" + length)
                            .header("Cache-Control", "private, max-age=300")
                            .body(new org.springframework.core.io.support.ResourceRegion(resource, start, regionLength));
                }
            } catch (Exception ignored) {
                // 坏 Range 头(解析失败/越界):退化为整体返回,浏览器自行处理
            }
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", mime)
                .header("Accept-Ranges", "bytes")
                .header("Cache-Control", "private, max-age=300")
                .body(resource);
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
