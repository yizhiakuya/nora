package com.nora.file.api;

/**
 * Upload payload for {@link FileService#upload(FileUploadRequest)}.
 *
 * @param name         file name to store and display
 * @param contentBytes raw file content, base64-encoded
 */
public record FileUploadRequest(
        String name,
        String contentBytes
) {
}
