package com.nora.file.api;

/**
 * {@link FileService#upload(FileUploadRequest)} 的上传载荷。
 *
 * @param name         存储与展示用文件名
 * @param contentBytes 原始文件内容(base64 编码)
 */
public record FileUploadRequest(
        String name,
        String contentBytes
) {
}
