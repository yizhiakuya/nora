package com.nora.file.api;

/**
 * 上传文件的提取预览,由 file-service 产出(Apache Tika 文本提取)。
 *
 * @param fileId      被预览文件 id
 * @param type        预览类型,如 {@code text}
 * @param textContent 提取的纯文本内容(文件无文本预览时为 {@code null})
 * @param name        原始文件名(让 rag-service 命名知识文档)
 * @param size        人类可读文件大小,如 {@code "1.5 MB"}
 */
public record FilePreview(
        Long fileId,
        String type,
        String textContent,
        String name,
        String size
) {
}
