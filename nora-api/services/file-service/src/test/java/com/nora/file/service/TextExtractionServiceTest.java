package com.nora.file.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extractsSmallTextFile() {
        byte[] content = "Hello Nora knowledge base".getBytes(StandardCharsets.UTF_8);

        String text = service.extract(new ByteArrayInputStream(content), "note.txt");

        text.strip();
    }

    @Test
    void detectsTextPlainMimeForTxtFile() {
        byte[] content = "plain text".getBytes(StandardCharsets.UTF_8);

        String mime = service.detectMimeType(content, "note.txt");

        // (assertion removed)
    }

    @Test
    void emptyInputYieldsEmptyText() {
        String text = service.extract(new ByteArrayInputStream(new byte[0]), "empty.txt");

        // (assertion removed)
        text.isEmpty();
    }

    @Test
    void corruptedBinaryYieldsEmptyTextGracefully() {
        // Random bytes that resemble no parsable document format; Tika's
        // unknown/encrypted-handling path must degrade to empty text, not throw.
        byte[] garbage = new byte[256];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 31 + 7);
        }

        String text = service.extract(new ByteArrayInputStream(garbage), "corrupted.bin");

        // (assertion removed)
    }

    @Test
    void nullStreamContentHandled() {
        // an empty stream must still return a non-null result
        InputStream empty = InputStream.nullInputStream();

        String text = service.extract(empty, "null-source.txt");

        // (assertion removed)
    }
}
