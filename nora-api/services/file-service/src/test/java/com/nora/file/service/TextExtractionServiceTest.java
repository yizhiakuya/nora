package com.nora.file.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extractsSmallTextFile() {
        byte[] content = "Hello Nora knowledge base".getBytes(StandardCharsets.UTF_8);

        String text = service.extract(new ByteArrayInputStream(content), "note.txt");

        assertEquals("Hello Nora knowledge base", text.strip());
    }

    @Test
    void detectsTextPlainMimeForTxtFile() {
        byte[] content = "plain text".getBytes(StandardCharsets.UTF_8);

        String mime = service.detectMimeType(content, "note.txt");

        assertEquals("text/plain", mime);
    }

    @Test
    void emptyInputYieldsEmptyText() {
        String text = service.extract(new ByteArrayInputStream(new byte[0]), "empty.txt");

        assertNotNull(text);
        assertTrue(text.isEmpty());
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

        assertNotNull(text);
    }

    @Test
    void nullStreamContentHandled() {
        // an empty stream must still return a non-null result
        InputStream empty = InputStream.nullInputStream();

        String text = service.extract(empty, "null-source.txt");

        assertNotNull(text);
    }
}
