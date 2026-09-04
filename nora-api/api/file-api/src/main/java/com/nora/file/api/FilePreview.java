package com.nora.file.api;

/**
 * Extracted preview of an uploaded file, produced by file-service
 * (Apache Tika text extraction).
 *
 * @param fileId      id of the previewed file
 * @param type        preview type, e.g. {@code text}
 * @param textContent extracted plain-text content ({@code null} when the file has no text preview)
 * @param name        original file name (lets rag-service name the knowledge doc)
 * @param size        human-readable file size, e.g. {@code "1.5 MB"}
 */
public record FilePreview(
        Long fileId,
        String type,
        String textContent,
        String name,
        String size
) {
}
