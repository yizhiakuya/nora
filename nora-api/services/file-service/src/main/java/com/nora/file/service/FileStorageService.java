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
        String originalName = file.getOriginalFilename();
        final String name = (originalName == null || originalName.isBlank()) ? "unnamed" : originalName;
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(400, "Could not read uploaded file: " + ex.getMessage(), ex);
        }
        String mimeType = textExtractionService.detectMimeType(content, name);

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
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO file_item (name, file_path, mime_type, size_bytes, indexed) VALUES (?, ?, ?, ?, ?)",
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
     * Lists file items, optionally filtered by ids.
     *
     * @param ids optional id filter; empty or {@code null} returns all files
     * @return matching file items ordered by id
     */
    public List<FileItem> list(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return jdbcTemplate.query("SELECT * FROM file_item WHERE deleted_at IS NULL ORDER BY id", this::mapRow);
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbcTemplate.query(
                "SELECT * FROM file_item WHERE id IN (" + placeholders + ") AND deleted_at IS NULL ORDER BY id",
                this::mapRow,
                ids.toArray());
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
                createdAt == null ? null : createdAt.toInstant());
    }

    /** Builds a collision-free storage path: {@code <storage-dir>/<id-less uuid>_<name>}. */
    private Path uniqueTarget(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String unique = java.util.UUID.randomUUID() + "_" + sanitized;
        return storageDir.resolve(unique);
    }
}
