package com.nora.file.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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

import com.nora.common.response.ApiResponse;
import com.nora.file.api.FileItem;
import com.nora.file.api.FilePreview;
import com.nora.file.client.RagIndexClient;
import com.nora.file.service.FileStorageService;

/**
 * 文件上传/列表/删除/预览/索引的 REST 端点。
 * 全部响应使用统一 {@link ApiResponse} 信封(驼峰 JSON)。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileStorageService fileStorageService;
    private final RagIndexClient ragIndexClient;
    private final com.nora.file.service.FileLifecycleService fileLifecycleService;

    public FileController(FileStorageService fileStorageService, RagIndexClient ragIndexClient,
                          com.nora.file.service.FileLifecycleService fileLifecycleService) {
        this.fileStorageService = fileStorageService;
        this.ragIndexClient = ragIndexClient;
        this.fileLifecycleService = fileLifecycleService;
    }

    /**
     * 上传文件(multipart),存储并持久化其元数据。
     *
     * @param file     multipart 文件部件
     * @param folderId 可选目标文件夹(文件页"上传到当前文件夹")
     * @return 落库的文件条目
     */
    @PostMapping("/upload")
    public ApiResponse<FileItem> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "folderId", required = false) Long folderId) {
        return ApiResponse.ok(fileStorageService.store(file, folderId));
    }

    /**
     * 列出文件。带 {@code ids} 时只返回这些 id(也用于轮询索引状态);
     * 否则返回全部文件。带 {@code folderId} 时只返回该文件夹内的文件。
     *
     * @param ids      可选逗号分隔 id 过滤
     * @param folderId 可选文件夹过滤
     * @return 匹配的文件条目
     */
    @GetMapping
    public ApiResponse<List<FileItem>> list(@RequestParam(value = "ids", required = false) String ids,
                                            @RequestParam(value = "folderId", required = false) Long folderId) {
        return ApiResponse.ok(fileStorageService.list(parseIds(ids), folderId));
    }

    /**
     * 按 ids 删除文件并从磁盘移除底层文件。
     *
     * <p>联动(2026-09-17):同步通知 rag-service 软删对应知识库文档——
     * 删除的文件不应继续被 AI 检索到。**提交边界(2026-09-26,F1)**:
     * 文件状态变更与通知入队在 FileLifecycleService 的同一事务内原子提交,
     * 投递在提交后进行(失败由待发送队列重试)。
     *
     * @param ids 逗号分隔 id 列表(必填)
     * @return {@code ok(null)}
     */
    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam("ids") String ids) {
        fileLifecycleService.delete(parseIds(ids));
        return ApiResponse.ok();
    }

    /**
     * 返回文件的提取纯文本预览。
     *
     * @param id 文件 id
     * @return 带类型与文本内容的预览
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
     * @param id    文件 id
     * @param range 可选 Range 头(形如 {@code bytes=0-1023})
     * @return 流式资源(200)或区间切片(206)
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

    /** 从回收站恢复文件(回到根目录;联动恢复知识库文档;F1:状态与通知入队同事务)。 */
    @PostMapping("/trash/restore")
    public ApiResponse<Integer> restore(@RequestBody MoveRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        // 过滤 null 元素:畸形 body(如 [null])会让 notify 的拆箱 NPE → 500
        List<Long> idList = request.ids().stream().filter(java.util.Objects::nonNull).toList();
        if (idList.isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        return ApiResponse.ok(fileLifecycleService.restore(idList).size());
    }

    /**
     * 永久删除(回收站清空):数据库行 + 磁盘文件一并删除。不可恢复。
     * 联动永久删除知识库文档与 chunk(F1:行删除与通知入队同事务,磁盘清理在提交后)。
     *
     * <p>只对**实际被清除**的 id 发联动通知——未删除的 id(列表过期/已被
     * 其他客户端恢复)若发 purge 通知,会把存活文件的知识库文档物理删除。
     */
    @DeleteMapping("/trash")
    public ApiResponse<Integer> purge(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            throw new com.nora.common.exception.BusinessException(400, "ids 不能为空");
        }
        return ApiResponse.ok(fileLifecycleService.purge(idList).size());
    }

    /**
     * 异步触发 rag-service 索引(发后即忘),立即返回当前文件条目
     * (此时 {@code indexed=false})。
     *
     * @param id 文件 id
     * @return 当前文件条目
     */
    @PostMapping("/{id}/index")
    public ApiResponse<FileItem> index(@PathVariable Long id) {
        FileItem item = fileStorageService.getById(id);
        ragIndexClient.triggerIndexAsync(item);
        return ApiResponse.ok(item);
    }

    /**
     * rag-service 的回调端点:把文件标记为已索引。
     *
     * @param id 文件 id
     * @return {@code indexed=true} 的更新后文件条目
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
     *
     * <p><b>单文件直出(2026-09-21 修复)</b>:此前单文件也套 zip 壳,但
     * Content-Disposition 用的是原名(无 .zip 后缀)——浏览器保存出
     * "xxx.txt" 而内容其实是 zip,双击打不开。现在单文件直接流式返回
     * 原文件(mime/文件名都是本来的),只有多文件才打包。
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
        if (items.size() == 1) {
            FileItem only = items.get(0);
            java.nio.file.Path path = fileStorageService.resolveFile(only.id());
            String encoded = java.net.URLEncoder.encode(only.name(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String mime = only.mimeType() == null || only.mimeType().isBlank()
                    ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
                    : only.mimeType();
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", mime)
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                    .body(out -> {
                        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                            in.transferTo(out);
                        }
                    });
        }
        String filename = "nora-files-" + items.size() + ".zip";
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
                            java.nio.file.Path path;
                            try {
                                path = fileStorageService.resolveFile(item.id());
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
                            // 流式拷贝(2026-09-19 审查修复):此前 rawBytes=readAllBytes
                            // 整文件进堆,大文件会打爆内存;这里按 64KB 块搬运
                            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                                in.transferTo(zip);
                            } catch (Exception e) {
                                log.warn("zip: stream copy failed {} ({}): {}", item.id(), item.name(), e.getMessage());
                            }
                            zip.closeEntry();
                        }
                    }
                });
    }

    /** 解析逗号分隔的 id 列表;空白/缺省产出空列表。
     *  非法数字(如 "abc")抛 400 而非 500——畸形输入是客户端错误。 */
    private List<Long> parseIds(String ids) {
        try {
            return Optional.ofNullable(ids)
                    .filter(s -> !s.isBlank())
                    .stream()
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException e) {
            throw new com.nora.common.exception.BusinessException(400,
                    "ids 必须是逗号分隔的数字,收到: " + ids);
        }
    }
}
