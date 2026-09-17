package com.nora.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.nora.common.exception.BusinessException;
import com.nora.file.api.FileItem;
import com.nora.file.api.FilePreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores uploaded files on the local filesystem and persists the metadata
 * rows ({@code schema_file.file_item}) via {@link JdbcTemplate}.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** Business code used when a file id is unknown. */
    public static final int FILE_NOT_FOUND_CODE = 404;

    private final Path storageDir;
    private final JdbcTemplate jdbcTemplate;
    private final TextExtractionService textExtractionService;

    public FileStorageService(@Value("${nora.file.storage-dir:./data/files/}") String storageDir,
                              JdbcTemplate jdbcTemplate,
                              TextExtractionService textExtractionService) {
        this.storageDir = Path.of(storageDir);
        this.jdbcTemplate = jdbcTemplate;
        this.textExtractionService = textExtractionService;
    }

    /**
     * Stores the uploaded file on disk and inserts the corresponding
     * {@code file_item} row.
     *
     * @param file multipart upload
     * @return the persisted file item
     */
    public FileItem store(MultipartFile file) {
        return store(file, null);
    }

    /**
     * Stores the uploaded file into an optional folder.
     *
     * @param file     multipart upload
     * @param folderId target folder ({@code null} = root)
     * @return the persisted file item
     */
    public FileItem store(MultipartFile file, Long folderId) {
        String originalName = file.getOriginalFilename();
        final String name = (originalName == null || originalName.isBlank()) ? "unnamed" : originalName;
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(400, "Could not read uploaded file: " + ex.getMessage(), ex);
        }
        String mimeType = textExtractionService.detectMimeType(content, name);
        if (folderId != null) {
            requireFolder(folderId);
        }

        long id;
        Path target;
        try {
            Files.createDirectories(storageDir);
            target = uniqueTarget(name);
            Files.copy(new java.io.ByteArrayInputStream(content), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException(500, "Could not store file '" + name + "': " + ex.getMessage(), ex);
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            Long targetFolder = folderId;
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO file_item (name, file_path, mime_type, size_bytes, indexed, folder_id) VALUES (?, ?, ?, ?, ?, ?)",
                        new String[] {"id"});
                ps.setString(1, name);
                ps.setString(2, target.toString());
                ps.setString(3, mimeType);
                if (content.length > 0) {
                    ps.setLong(4, content.length);
                } else {
                    ps.setNull(4, Types.BIGINT);
                }
                ps.setBoolean(5, false);
                if (targetFolder == null) {
                    ps.setNull(6, Types.BIGINT);
                } else {
                    ps.setLong(6, targetFolder);
                }
                return ps;
            }, keyHolder);
            id = keyHolder.getKey().longValue();
        } catch (RuntimeException ex) {
            // DB insert failed: do not leave the already-written file orphaned on disk
            try {
                Files.deleteIfExists(target);
            } catch (IOException ioEx) {
                log.warn("Could not clean up orphaned file '{}': {}", target, ioEx.getMessage());
            }
            throw ex;
        }
        return getById(id);
    }

    /**
     * Lists file items, optionally filtered by ids and/or folder.
     *
     * @param ids      optional id filter; empty or {@code null} returns all files
     * @param folderId folder filter; {@code null} = no filter (all folders)
     * @return matching file items ordered by id
     */
    public List<FileItem> list(List<Long> ids, Long folderId) {
        if (ids == null || ids.isEmpty()) {
            if (folderId == null) {
                return jdbcTemplate.query("SELECT * FROM file_item WHERE deleted_at IS NULL ORDER BY id", this::mapRow);
            }
            return jdbcTemplate.query(
                    "SELECT * FROM file_item WHERE deleted_at IS NULL AND folder_id = ? ORDER BY id",
                    this::mapRow, folderId);
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbcTemplate.query(
                "SELECT * FROM file_item WHERE id IN (" + placeholders + ") AND deleted_at IS NULL ORDER BY id",
                this::mapRow,
                ids.toArray());
    }

    /** Lists files (compat overload: no folder filter). */
    public List<FileItem> list(List<Long> ids) {
        return list(ids, null);
    }

    // ---------- 文件夹(2026-09-17:文件中心真实目录组织) ----------

    /** 文件夹行(含文件数,列表页展示用)。 */
    public record FolderRow(Long id, String name, int fileCount, Instant createdAt) {
    }

    /** 列出全部文件夹(含各自存活文件数),按名称排序。 */
    public List<FolderRow> listFolders() {
        return jdbcTemplate.query(
                """
                SELECT f.id, f.name, f.created_at,
                       (SELECT count(*) FROM file_item i
                         WHERE i.folder_id = f.id AND i.deleted_at IS NULL) AS file_count
                  FROM file_folder f
                 ORDER BY f.name
                """,
                (rs, i) -> new FolderRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("file_count"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()));
    }

    /** 创建文件夹(名称重复时报 409 语义错误)。 */
    public FolderRow createFolder(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "文件夹名不能为空");
        }
        if (trimmed.length() > 120) {
            throw new BusinessException(400, "文件夹名过长(最多 120 字)");
        }
        if (folderNameExists(trimmed, null)) {
            throw new BusinessException(409, "已存在同名文件夹:「" + trimmed + "」");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO file_folder (name) VALUES (?)", new String[] {"id"});
            ps.setString(1, trimmed);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new FolderRow(id, trimmed, 0, Instant.now());
    }

    /** 重命名文件夹。 */
    public FolderRow renameFolder(Long id, String name) {
        requireFolder(id);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "文件夹名不能为空");
        }
        if (folderNameExists(trimmed, id)) {
            throw new BusinessException(409, "已存在同名文件夹:「" + trimmed + "」");
        }
        jdbcTemplate.update("UPDATE file_folder SET name = ? WHERE id = ?", trimmed, id);
        int count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM file_item WHERE folder_id = ? AND deleted_at IS NULL", Integer.class, id);
        return new FolderRow(id, trimmed, count, null);
    }

    /**
     * 删除文件夹:其中的文件回到根目录(folder_id 置 NULL),不删除文件本身。
     * 返回移回根目录的文件数。
     */
    public int deleteFolder(Long id) {
        requireFolder(id);
        int moved = jdbcTemplate.update(
                "UPDATE file_item SET folder_id = NULL WHERE folder_id = ? AND deleted_at IS NULL", id);
        jdbcTemplate.update("DELETE FROM file_folder WHERE id = ?", id);
        return moved;
    }

    /** 重命名文件。 */
    public FileItem renameFile(Long id, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        if (trimmed.length() > 255) {
            throw new BusinessException(400, "文件名过长(最多 255 字)");
        }
        getById(id);
        jdbcTemplate.update("UPDATE file_item SET name = ? WHERE id = ? AND deleted_at IS NULL", trimmed, id);
        return getById(id);
    }

    /**
     * 移动文件到文件夹(null = 根目录);支持批量。
     *
     * @return 实际移动的文件数
     */
    public int moveFiles(List<Long> ids, Long folderId) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "ids parameter is required");
        }
        if (folderId != null) {
            requireFolder(folderId);
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        List<Object> args = new ArrayList<>();
        if (folderId == null) {
            args.add(null);
        } else {
            args.add(folderId);
        }
        args.addAll(ids);
        return jdbcTemplate.update(
                "UPDATE file_item SET folder_id = ? WHERE id IN (" + placeholders + ") AND deleted_at IS NULL",
                args.toArray());
    }

    /** 按 id 读取文件原始字节(批量下载打包用)。 */
    public byte[] rawBytes(Long id) {
        Path path = resolveFile(id);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件失败: " + e.getMessage(), e);
        }
    }

    private boolean folderNameExists(String name, Long excludeId) {
        Integer count = excludeId == null
                ? jdbcTemplate.queryForObject("SELECT count(*) FROM file_folder WHERE name = ?", Integer.class, name)
                : jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM file_folder WHERE name = ? AND id <> ?", Integer.class, name, excludeId);
        return count != null && count > 0;
    }

    private void requireFolder(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM file_folder WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(404, "文件夹不存在: " + id);
        }
    }

    /**
     * Soft-deletes file items by ids; the backing files stay on disk
     * (data is never physically destroyed; queries filter deleted rows out).
     * Unknown ids are skipped silently.
     *
     * @param ids ids to delete; must not be empty
     * @return number of soft-deleted rows
     */
    public int delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "ids parameter is required");
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbcTemplate.update(
                "UPDATE file_item SET deleted_at = now() WHERE id IN (" + placeholders + ") AND deleted_at IS NULL",
                ids.toArray());
    }

    // ---------- 回收站(2026-09-17:兑现"数据不真丢"的承诺) ----------

    /** 回收站条目(含删除时间)。 */
    public record TrashedItem(FileItem item, Instant deletedAt) {
    }

    /** 列出回收站中的文件(按删除时间倒序,最近删的先显示)。 */
    public List<TrashedItem> listTrash() {
        return jdbcTemplate.query(
                "SELECT * FROM file_item WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC",
                (rs, i) -> new TrashedItem(
                        mapRow(rs, i),
                        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()));
    }

    /** 从回收站恢复文件(回到根目录,避免原文件夹已删除的悬空引用)。 */
    public int restore(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "ids parameter is required");
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbcTemplate.update(
                "UPDATE file_item SET deleted_at = NULL, folder_id = NULL WHERE id IN (" + placeholders + ") AND deleted_at IS NOT NULL",
                ids.toArray());
    }

    /**
     * 永久删除(从回收站清空):软删除行 + 磁盘文件一并删除。不可恢复。
     *
     * @return 实际删除的文件数
     */
    public int purge(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "ids parameter is required");
        }
        int count = 0;
        for (Long id : ids) {
            List<String> paths = new ArrayList<>();
            jdbcTemplate.query(
                    "SELECT file_path FROM file_item WHERE id = ? AND deleted_at IS NOT NULL",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> paths.add(rs.getString("file_path")),
                    id);
            if (paths.isEmpty()) {
                continue;
            }
            jdbcTemplate.update("DELETE FROM file_item WHERE id = ?", id);
            // 磁盘文件删除失败不阻断(数据行已清;残留文件后续可人工回收)
            try {
                if (paths.get(0) != null) {
                    Files.deleteIfExists(Path.of(paths.get(0)));
                }
            } catch (Exception e) {
                log.warn("purge: could not delete file on disk for id {}: {}", id, e.getMessage());
            }
            count++;
        }
        return count;
    }

    /** Reads only the {@code file_path} column for the given id. */
    private String filePathOf(Long id) {
        List<String> paths = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT file_path FROM file_item WHERE id = ? AND deleted_at IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> paths.add(rs.getString("file_path")),
                id);
        return paths.isEmpty() ? null : paths.get(0);
    }

    /**
     * Fetches a single file item by id.
     *
     * @param id file id
     * @return the file item
     * @throws BusinessException 404 when the id is unknown
     */
    public FileItem getById(Long id) {
        List<FileItem> items = jdbcTemplate.query(
                "SELECT * FROM file_item WHERE id = ? AND deleted_at IS NULL", this::mapRow, id);
        if (items.isEmpty()) {
            throw new BusinessException(FILE_NOT_FOUND_CODE, "File not found: " + id);
        }
        return items.get(0);
    }

    /**
     * Extracts a plain-text preview for the stored file (Apache Tika).
     *
     * @param id file id
     * @return preview with {@code type=text} and the extracted content
     * @throws BusinessException 404 when the id is unknown
     */
    public FilePreview preview(Long id) {
        FileItem item = getById(id);
        String text = "";
        String filePath = filePathOf(id);
        if (filePath != null && Files.exists(Path.of(filePath))) {
            try (InputStream in = Files.newInputStream(Path.of(filePath))) {
                text = textExtractionService.extract(in, item.name());
            } catch (IOException ex) {
                log.warn("Preview extraction failed for file {}: {}", id, ex.getMessage());
            }
        }
        return new FilePreview(id, "text", text, item.name(), humanReadableSize(item.sizeBytes()));
    }

    /**
     * 读取文件原始字节(图片/PDF 等二进制的直接预览用)。
     *
     * <p>预览端点走 Tika 只能给文本,图片拿不到内容;文件中心里点开图片
     * 需要原始字节,这里按 id 定位磁盘文件读回。
     *
     * @param id file id
     * @return 文件字节
     * @throws BusinessException 404 when the id is unknown or the file is missing on disk
     */
    public byte[] raw(Long id) {
        FileItem item = getById(id);
        String filePath = filePathOf(id);
        if (filePath == null || !Files.exists(Path.of(filePath))) {
            throw new BusinessException(FILE_NOT_FOUND_CODE, "File content not found: " + item.name());
        }
        try {
            return Files.readAllBytes(Path.of(filePath));
        } catch (IOException ex) {
            throw new BusinessException(500, "Could not read file '" + item.name() + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * 解析文件在磁盘上的路径(视频/大文件流式读取用;2026-09-17)。
     *
     * <p>与 {@link #raw(Long)} 的区别:不把字节读进内存——Controller 用
     * {@code FileSystemResource} 流式返回,支持 HTTP Range(视频拖进度条必需)。
     *
     * @param id file id
     * @return 磁盘路径
     * @throws BusinessException 404 when the id is unknown or the file is missing on disk
     */
    public Path resolveFile(Long id) {
        FileItem item = getById(id);
        String filePath = filePathOf(id);
        if (filePath == null || !Files.exists(Path.of(filePath))) {
            throw new BusinessException(FILE_NOT_FOUND_CODE, "File content not found: " + item.name());
        }
        return Path.of(filePath);
    }

    private static String humanReadableSize(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024);
    }

    /**
     * Marks a file as indexed (callback from rag-service).
     *
     * @param id file id
     * @return the updated file item
     * @throws BusinessException 404 when the id is unknown
     */
    public FileItem markIndexed(Long id) {
        getById(id);
        jdbcTemplate.update("UPDATE file_item SET indexed = true WHERE id = ? AND deleted_at IS NULL", id);
        return getById(id);
    }

    private FileItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new FileItem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("mime_type"),
                rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"),
                rs.getBoolean("indexed"),
                createdAt == null ? null : createdAt.toInstant(),
                rs.getObject("folder_id") == null ? null : rs.getLong("folder_id"));
    }

    /** Builds a collision-free storage path: {@code <storage-dir>/<id-less uuid>_<name>}. */
    private Path uniqueTarget(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String unique = java.util.UUID.randomUUID() + "_" + sanitized;
        return storageDir.resolve(unique);
    }
}
