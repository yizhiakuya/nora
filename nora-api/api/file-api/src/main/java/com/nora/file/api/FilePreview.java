package com.nora.file.api;

/**
 * Extracted preview of an uploaded file, produced by file-service
 * (Apache Tika text extraction).
 *
 * @param fileId      id of the previewed file
 * @param type        preview type, e.g. {@code text}
 * @param textContent extracted plain-text content ({@code null} when the file has no text preview)
 */
public record FilePreview(
        Long fileId,
        String type,
        String textContent
) {
}
