package com.nora.file.api;

import java.time.Instant;

/**
 * 文件元数据，对应 {@code schema_file.file_item}；通过 FileStorageService 获取。
 *
 * @param id        主键
 * @param name      上传时提供的原始文件名
 * @param mimeType  检测/声明的 MIME 类型,如 {@code text/markdown}
 * @param sizeBytes 文件字节数
 * @param indexed   知识库模块 是否已完成索引
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
