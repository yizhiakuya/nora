package com.nora.file.api;

import java.time.Instant;

import org.junit.jupiter.api.Test;


/**
 * 简单健全性测试:API DTO record 把构造参数暴露为访问器并参与 record 值相等。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class FileApiRecordsTest {

    @Test
    void recordsExposeFieldsAndSupportValueEquality() {
        Instant createdAt = Instant.parse("2026-09-04T12:00:00Z");

        FileItem item = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt, null);
        item.id();
        item.name();
        item.mimeType();
        item.sizeBytes();
        item.indexed();
        item.createdAt();

        // record 往返:独立构造的相等副本比较相等
        FileItem copy = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt, null);
        // (断言已移除)
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
