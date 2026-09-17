package com.nora.file.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.nora.common.response.ApiResponse;
import com.nora.file.api.FileItem;
import com.nora.file.api.FilePreview;
import com.nora.file.client.RagIndexClient;
import com.nora.file.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileStorageService fileStorageService;
    private final RagIndexClient ragIndexClient;

    public FileController(FileStorageService fileStorageService, RagIndexClient ragIndexClient) {
        this.fileStorageService = fileStorageService;
        this.ragIndexClient = ragIndexClient;
    }

    /**
     * Uploads a file (multipart), stores it, and persists its metadata.
     *
     * @param file     multipart file part
     * @param folderId optional target folder (files page "upload into current folder")
     * @return the persisted file item
     */
    @PostMapping("/upload")
    public ApiResponse<FileItem> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "folderId", required = false) Long folderId) {
        return ApiResponse.ok(fileStorageService.store(file, folderId));
    }

    /**
     * Lists files. When {@code ids} is present, only those ids are returned
     * (also used to poll indexing state); otherwise all files are returned.
     * When {@code folderId} is present, only files in that folder are returned.
     *
     * @param ids      optional comma-separated id filter
     * @param folderId optional folder filter
     * @return matching file items
     */
    @GetMapping
    public ApiResponse<List<FileItem>> list(@RequestParam(value = "ids", required = false) String ids,
                                            @RequestParam(value = "folderId", required = false) Long folderId) {
        return ApiResponse.ok(fileStorageService.list(parseIds(ids), folderId));
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

    // ---------- 回收站(2026-09-17) ----------

    /** 回收站列表(软删除的文件;按删除时间倒序)。 */
    @GetMapping("/trash")
    public ApiResponse<List<FileStorageService.TrashedItem>> trash() {
        return ApiResponse.ok(fileStorageService.listTrash());
    }

    /** 从回收站恢复文件(回到根目录)。 */
    @PostMapping("/trash/restore")
    public ApiResponse<Integer> restore(@RequestBody MoveRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        return ApiResponse.ok(fileStorageService.restore(request.ids()));
    }

    /**
     * 永久删除(回收站清空):数据库行 + 磁盘文件一并删除。不可恢复。
     */
    @DeleteMapping("/trash")
    public ApiResponse<Integer> purge(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        return ApiResponse.ok(fileStorageService.purge(idList));
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

    // ---------- 文件夹(2026-09-17) ----------

    /** 文件夹列表(含各自存活文件数)。 */
    @GetMapping("/folders")
    public ApiResponse<List<FileStorageService.FolderRow>> folders() {
        return ApiResponse.ok(fileStorageService.listFolders());
    }

    /** 新建文件夹。 */
    @PostMapping("/folders")
    public ApiResponse<FileStorageService.FolderRow> createFolder(@RequestBody FolderRequest request) {
        return ApiResponse.ok(fileStorageService.createFolder(request == null ? null : request.name()));
    }

    /** 重命名文件夹。 */
    @org.springframework.web.bind.annotation.PutMapping("/folders/{id}")
    public ApiResponse<FileStorageService.FolderRow> renameFolder(@PathVariable Long id,
                                                                  @RequestBody FolderRequest request) {
        return ApiResponse.ok(fileStorageService.renameFolder(id, request == null ? null : request.name()));
    }

    /**
     * 删除文件夹(其中的文件回到根目录,不连带删除文件)。
     *
     * @return 移回根目录的文件数
     */
    @DeleteMapping("/folders/{id}")
    public ApiResponse<Integer> deleteFolder(@PathVariable Long id) {
        return ApiResponse.ok(fileStorageService.deleteFolder(id));
    }

    /** POST/PUT 文件夹请求体。 */
    public record FolderRequest(String name) {
    }

    /** 重命名单个文件。 */
    @org.springframework.web.bind.annotation.PutMapping("/{id}/name")
    public ApiResponse<FileItem> renameFile(@PathVariable Long id, @RequestBody FolderRequest request) {
        return ApiResponse.ok(fileStorageService.renameFile(id, request == null ? null : request.name()));
    }

    /**
     * 批量移动文件到文件夹({@code folderId} 缺省/null = 根目录)。
     *
     * @return 实际移动的文件数
     */
    @org.springframework.web.bind.annotation.PutMapping("/move")
    public ApiResponse<Integer> move(@RequestBody MoveRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        return ApiResponse.ok(fileStorageService.moveFiles(request.ids(), request.folderId()));
    }

    /** PUT /api/files/move body。 */
    public record MoveRequest(List<Long> ids, Long folderId) {
    }

    /**
     * 批量下载:把选中文件打包为 zip 流式返回(浏览器直接触发下载)。
     *
     * <p>为什么需要:文件中心此前"下载"按钮是假的(toast 提示)。批量场景
     * (一次导出整个项目资料)必须服务端打包——逐个下载会被浏览器拦截
     * 且体验割裂。zip 用流式写出,大文件不整包进内存。
     */
    @GetMapping("/download")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        List<FileItem> items = fileStorageService.list(idList);
        if (items.isEmpty()) {
            throw new com.nora.common.exception.BusinessException(404, "没有可下载的文件");
        }
        String filename = items.size() == 1
                ? "download-" + items.get(0).name()
                : "nora-files-" + items.size() + ".zip";
        // 文件名含中文时 RFC 5987 编码,否则头值非法
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .body(out -> {
                    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                        java.util.Set<String> used = new java.util.HashSet<>();
                        for (FileItem item : items) {
                            byte[] bytes;
                            try {
                                bytes = fileStorageService.rawBytes(item.id());
                            } catch (Exception e) {
                                // 单个文件读取失败不拖垮整个包:跳过并留一条说明
                                log.warn("zip: skip unreadable file {} ({}): {}", item.id(), item.name(), e.getMessage());
                                continue;
                            }
                            String entryName = item.name();
                            if (!used.add(entryName)) {
                                entryName = item.id() + "_" + entryName;
                                used.add(entryName);
                            }
                            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            zip.write(bytes);
                            zip.closeEntry();
                        }
                    }
                });
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
