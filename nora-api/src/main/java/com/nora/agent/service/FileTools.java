package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;
import com.nora.file.api.FileItem;
import com.nora.file.service.FileLifecycleService;
import com.nora.file.service.FileStorageService;

/** 文件工具负责有界展示，文件校验、存储和生命周期复用领域服务。 */
@Service
public class FileTools {
    private static final Logger log = LoggerFactory.getLogger(FileTools.class);
    private final FileStorageService files;
    private final FileLifecycleService lifecycle;

    public FileTools(FileStorageService files, FileLifecycleService lifecycle) {
        this.files = files;
        this.lifecycle = lifecycle;
    }

    public String list() {
        var items = files.list(null);
        if (items.isEmpty()) return "(还没有上传任何文件)";
        var names = files.listFolders().stream().collect(java.util.stream.Collectors.toMap(FileStorageService.FolderRow::id,
                FileStorageService.FolderRow::name));
        return items.stream().map(row -> "id=" + row.id() + " | " + row.name() + " | " + formatSize(row.sizeBytes() == null ? 0 : row.sizeBytes())
                + " | " + (Boolean.TRUE.equals(row.indexed()) ? "indexed" : "not-indexed")
                + (row.folderId() == null ? "" : " | 文件夹:" + names.getOrDefault(row.folderId(), "#" + row.folderId())))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 精确名称优先，再不区分大小写；同名取最新 id。 */
    public Long findIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        String wanted = name.trim().startsWith("@center/") ? name.trim().substring(8) : name.trim();
        var items = files.list(null);
        var exact = items.stream().filter(row -> wanted.equals(row.name())).map(FileItem::id).max(Long::compareTo);
        return exact.orElseGet(() -> items.stream().filter(row -> wanted.equalsIgnoreCase(row.name()))
                .map(FileItem::id).max(Long::compareTo).orElse(null));
    }

    public String preview(long id) {
        var info = previewInfo(id);
        if (info.failed()) return "ERROR: " + info.error();
        return info.hasText() ? renderPreview(info) : "文件 " + info.name() + " 没有可提取的文本内容(可能是二进制/图片)";
    }

    public record PreviewInfo(String name, String text, String error) {
        public boolean failed() {
            return error != null;
        }

        public boolean hasText() {
            return error == null && text != null && !text.isBlank();
        }
    }

    public PreviewInfo previewInfo(long id) {
        try {
            var preview = files.preview(id);
            return new PreviewInfo(preview.name(), preview.textContent(), preview.extractError());
        } catch (BusinessException e) { return new PreviewInfo(null, null, e.getMessage()); }
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

    public record RawFile(String mimeType, byte[] bytes) {}

    public FileItem metadata(long id) {
        try { return files.getById(id); }
        catch (BusinessException e) { if (e.getCode() == 404) return null; throw e; }
    }

    public byte[] rawBounded(long id, int limit) throws java.io.IOException {
        try (var input = java.nio.file.Files.newInputStream(files.resolveFile(id))) { return input.readNBytes(limit); }
    }

    public RawFile raw(long id) {
        try { return new RawFile(files.getById(id).mimeType(), files.raw(id)); }
        catch (BusinessException e) { log.warn("file {} bytes unavailable: {}", id, e.getMessage()); return null; }
    }

    public String upload(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "ERROR: 上传内容为空";
        var file = files.store(filename == null || filename.isBlank() ? "import.bin" : filename, bytes, null);
        return "已存入工作台文件 id=" + file.id() + " | " + file.name() + " | " + formatSize(bytes.length);
    }

    public String rename(long id, String name) { return "已重命名为 " + files.renameFile(id, name).name() + "(id=" + id + ")"; }

    public String move(List<Long> ids, Long folderId) {
        return "已移动 " + files.moveFiles(ids, folderId) + " 个文件到" + (folderId == null ? "根目录" : "文件夹 id=" + folderId);
    }

    public String delete(List<Long> ids) {
        return "已把 " + lifecycle.delete(ids).size() + " 个文件移入回收站(可恢复；彻底删除需在文件页操作)";
    }

    public String folders() {
        var folders = files.listFolders();
        return folders.isEmpty() ? "(尚无文件夹)可用 mkdir 建一个" : folders.stream().map(row -> "id=" + row.id()
                + " | " + row.name() + " | 文件数 " + row.fileCount()).collect(java.util.stream.Collectors.joining("\n"));
    }

    public String mkdir(String name) {
        var folder = files.createFolder(name);
        return "已创建文件夹「" + folder.name() + "」id=" + folder.id();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
    }
}
