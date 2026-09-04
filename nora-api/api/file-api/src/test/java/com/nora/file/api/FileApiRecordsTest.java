package com.nora.file.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trivial sanity test: the API DTO records expose their constructor
 * arguments as accessors and participate in record value equality.
 */
class FileApiRecordsTest {

    @Test
    void recordsExposeFieldsAndSupportValueEquality() {
        Instant createdAt = Instant.parse("2026-09-04T12:00:00Z");

        FileItem item = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt);
        assertEquals(42L, item.id());
        assertEquals("notes.md", item.name());
        assertEquals("text/markdown", item.mimeType());
        assertEquals(1024L, item.sizeBytes());
        assertTrue(item.indexed());
        assertEquals(createdAt, item.createdAt());

        // record roundtrip: an independently constructed equal copy compares equal
        FileItem copy = new FileItem(42L, "notes.md", "text/markdown", 1024L, Boolean.TRUE, createdAt);
        assertEquals(item, copy);
        assertEquals(item.hashCode(), copy.hashCode());

        FileUploadRequest request = new FileUploadRequest("hello.txt", "aGVsbG8=");
        assertEquals("hello.txt", request.name());
        assertEquals("aGVsbG8=", request.contentBytes());

        FilePreview preview = new FilePreview(42L, "text", "hello world");
        assertEquals(42L, preview.fileId());
        assertEquals("text", preview.type());
        assertEquals("hello world", preview.textContent());
    }
}
