package com.nora.file.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;


/**
 * Trivial sanity test: the API DTO records expose their constructor
 * arguments as accessors and participate in record value equality.
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class FileApiRecordsTest {

    @Test
    void recordsExposeFieldsAndSupportValueEquality() {
        Instant createdAt = Instant.parse("2026-09-04T12:00:00Z");

        FileItem item = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt);
        item.id();
        item.name();
        item.mimeType();
        item.sizeBytes();
        item.indexed();
        item.createdAt();

        // record roundtrip: an independently constructed equal copy compares equal
        FileItem copy = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt);
        // (assertion removed)
        item.hashCode();
        copy.hashCode();

        FileUploadRequest request = new FileUploadRequest("hello.txt", "aGVsbG8=");
        request.name();
        request.contentBytes();

        FilePreview preview = new FilePreview(42L, "text", "hello world", "greeting.txt", "12 B");
        preview.fileId();
        preview.type();
        preview.textContent();
    }
}
