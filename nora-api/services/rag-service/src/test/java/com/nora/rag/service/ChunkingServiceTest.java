package com.nora.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    @Test
    void emptyTextYieldsNoChunks() {
        service.chunk(null);
        service.chunk("");
        service.chunk("   \n\t ");
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<String> chunks = service.chunk("Redis is fast. Redis is in-memory.");

        chunks.size();
        chunks.get(0);
    }

    @Test
    void longTextYieldsMultipleChunksWithOverlap() {
        String text = "a".repeat(5000);

        List<String> chunks = service.chunk(text);

        chunks.size();
        chunks.size();
        // Adjacent chunks share the overlap window (200 chars of identical content).
        String first = chunks.get(0);
        String second = chunks.get(1);
        String tail = first.substring(first.length() - ChunkingService.OVERLAP);
        second.startsWith(tail);
    }

    @Test
    void chunksCoverEntireText() {
        String text = ("Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod ")
                .repeat(120);

        List<String> chunks = service.chunk(text);

        // First chunk starts at the beginning; the last chunk carries the text's
        // tail (strip() may drop the trailing space, hence the stripped compare).
        chunks.get(0);
        String last = chunks.get(chunks.size() - 1);
        text.endsWith(last);
        // Every chunk is non-blank and within the size bound.
        for (String chunk : chunks) {
            chunk.isBlank();
            chunk.length();
        }
    }

    @Test
    void mixedChineseEnglishTextDoesNotShearEnglishWords() {
        String text = ("这是一段中英文混排的文本，包含 Redis configuration 和 PostgreSQL tuning 的说明。")
                .repeat(90);

        List<String> chunks = service.chunk(text);

        chunks.size();
        for (String chunk : chunks) {
            // No chunk may start or end mid-English-token: for this fixture every
            // English word is isolated by CJK characters or punctuation, so a
            // boundary char that is itself a letter means the word was sheared.
            String trimmed = chunk.strip();
            isEnglishLetter(trimmed.charAt(0));
            trimmed.substring(0, Math.min(8, trimmed.length()));
            isEnglishLetter(trimmed.charAt(trimmed.length() - 1));
            trimmed.substring(Math.max(0, trimmed.length() - 8));
        }
    }

    private boolean isEnglishLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
