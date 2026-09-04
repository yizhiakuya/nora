package com.nora.file.api;

/**
 * Dubbo RPC contract for file-service. The provider side (file-service)
 * implements this interface and registers it to Nacos; consumer services
 * inject it with {@code @DubboReference}.
 *
 * @see FileItem
 * @see FileUploadRequest
 * @see FilePreview
 */
public interface FileService {

    /**
     * Uploads a new file. The provider persists the file item, stores the
     * binary content, and emits the {@code file.uploaded} event (outbox)
     * so rag-service can index it.
     *
     * @param request upload payload (name + base64 content)
     * @return the persisted file item
     */
    FileItem upload(FileUploadRequest request);

    /**
     * Returns the extracted plain-text preview for an uploaded file.
     *
     * @param fileId id of the file
     * @return preview with type and text content
     */
    FilePreview preview(Long fileId);

    /**
     * Fetches a single file item by id — the supported way for other
     * services to read {@code file_item} rows owned by file-service
     * (cross-schema JOIN is forbidden).
     *
     * @param id file id
     * @return the file item
     */
    FileItem getById(Long id);
}
