package com.nora.file.api;

import java.time.Instant;

/**
 * A file managed by file-service. Mirrors the {@code file_item} table
 * (schema_file). Owned by file-service; other services obtain it via
 * {@link FileService#getById(Long)} instead of cross-schema reads.
 *
 * @param id        primary key
 * @param name      original file name supplied at upload
 * @param mimeType  detected/declared MIME type, e.g. {@code text/markdown}
 * @param sizeBytes file size in bytes
 * @param indexed   whether rag-service finished indexing this file
 * @param createdAt upload timestamp (UTC)
 * @param folderId  owning folder id; {@code null} = root (no folder)
 */
public record FileItem(
        Long id,
        String name,
        String mimeType,
        Long sizeBytes,
        Boolean indexed,
        Instant createdAt,
        Long folderId
) {
}
