package com.nora.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    @Test
    void emptyTextYieldsNoChunks() {
        assertEquals(0, service.chunk(null).size());
        assertEquals(0, service.chunk("").size());
        assertEquals(0, service.chunk("   \n\t ").size());
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<String> chunks = service.chunk("Redis is fast. Redis is in-memory.");

        assertEquals(1, chunks.size());
        assertEquals("Redis is fast. Redis is in-memory.", chunks.get(0));
    }

    @Test
    void longTextYieldsMultipleChunksWithOverlap() {
        String text = "a".repeat(5000);

        List<String> chunks = service.chunk(text);

        assertTrue(chunks.size() >= 2, "expected multiple chunks, got " + chunks.size());
        // Adjacent chunks share the overlap window (200 chars of identical content).
        String first = chunks.get(0);
        String second = chunks.get(1);
        String tail = first.substring(first.length() - ChunkingService.OVERLAP);
        assertTrue(second.startsWith(tail),
                "second chunk should start with the overlap tail of the first");
    }

    @Test
    void chunksCoverEntireText() {
        String text = ("Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod ")
                .repeat(120);

        List<String> chunks = service.chunk(text);

        // First chunk starts at the beginning; the last chunk carries the text's
        // tail (strip() may drop the trailing space, hence the stripped compare).
        assertTrue(chunks.get(0).startsWith("Lorem"));
        String last = chunks.get(chunks.size() - 1);
        assertTrue(text.endsWith(last) || text.strip().endsWith(last),
                "last chunk should be a suffix of the source text");
        // Every chunk is non-blank and within the size bound.
        for (String chunk : chunks) {
            assertTrue(!chunk.isBlank());
            assertTrue(chunk.length() <= ChunkingService.CHUNK_SIZE);
        }
    }

    @Test
    void mixedChineseEnglishTextDoesNotShearEnglishWords() {
        String text = ("这是一段中英文混排的文本，包含 Redis configuration 和 PostgreSQL tuning 的说明。")
                .repeat(90);

        List<String> chunks = service.chunk(text);

        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            // No chunk may start or end mid-English-token: for this fixture every
            // English word is isolated by CJK characters or punctuation, so a
            // boundary char that is itself a letter means the word was sheared.
            String trimmed = chunk.strip();
            assertTrue(!isEnglishLetter(trimmed.charAt(0)),
                    "chunk starts mid-word: " + trimmed.substring(0, Math.min(8, trimmed.length())));
            assertTrue(!isEnglishLetter(trimmed.charAt(trimmed.length() - 1)),
                    "chunk ends mid-word: " + trimmed.substring(Math.max(0, trimmed.length() - 8)));
        }
    }

    private boolean isEnglishLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
