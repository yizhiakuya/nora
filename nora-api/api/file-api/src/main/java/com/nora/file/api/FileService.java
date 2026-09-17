package com.nora.file.api;

/**
 * file-service 的 Dubbo RPC 契约。提供方(file-service)实现本接口并注册到
 * Nacos;消费方服务经 {@code @DubboReference} 注入。
 *
 * @see FileItem
 * @see FileUploadRequest
 * @see FilePreview
 */
public interface FileService {

    /**
     * 上传新文件。提供方持久化文件条目、存储二进制内容,并发出
     * {@code file.uploaded} 事件(outbox),让 rag-service 可索引它。
     *
     * @param request 上传载荷(名称 + base64 内容)
     * @return 落库的文件条目
     */
    FileItem upload(FileUploadRequest request);

    /**
     * 返回上传文件的提取纯文本预览。
     *
     * @param fileId 文件 id
     * @return 带类型与文本内容的预览
     */
    FilePreview preview(Long fileId);

    /**
     * 按 id 取单个文件条目——其他服务读取 file-service 所属
     * {@code file_item} 行的受支持方式(禁止跨 schema JOIN)。
     *
     * @param id 文件 id
     * @return 文件条目
     */
    FileItem getById(Long id);
}
