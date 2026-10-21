package com.nora.file.api;

import java.time.Instant;

import org.junit.jupiter.api.Test;


/**
 * API record 构造与访问器冒烟。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class FileApiRecordsTest {

    @Test
    void recordsExposeFields() {
        Instant createdAt = Instant.parse("2026-09-04T12:00:00Z");

        FileItem item = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt, null);
        item.id();
        item.name();
        item.mimeType();
        item.sizeBytes();
        item.indexed();
        item.createdAt();

        FilePreview preview = new FilePreview(42L, "text", "hello world", "greeting.txt", "12 B");
        preview.fileId();
        preview.type();
        preview.textContent();
    }
}
