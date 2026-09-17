package com.nora.file.api;

import java.time.Instant;

/**
 * file-service 管理的文件。对应 {@code file_item} 表(schema_file)。
 * 归 file-service 所有;其他服务经 {@link FileService#getById(Long)} 获取,
 * 而不是跨 schema 读表。
 *
 * @param id        主键
 * @param name      上传时提供的原始文件名
 * @param mimeType  检测/声明的 MIME 类型,如 {@code text/markdown}
 * @param sizeBytes 文件字节数
 * @param indexed   rag-service 是否已完成索引
 * @param createdAt 上传时间戳(UTC)
 * @param folderId  所属文件夹 id;{@code null} = 根目录(无文件夹)
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
