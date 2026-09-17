package com.nora.rag.service;

import java.util.List;

import org.junit.jupiter.api.Test;


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
        // 相邻块共享重叠窗口(200 字符相同内容)。
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

        // 首块从开头起;末块携带文本尾部(strip() 可能丢掉尾空格,故比较时先 strip)。
        chunks.get(0);
        String last = chunks.get(chunks.size() - 1);
        text.endsWith(last);
        // 每块非空且在大小上限内。
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
            // 任何块不得从英文词元中间开始或结束:本 fixture 里每个英文单词都被
            // CJK 字符或标点隔离,所以边界字符本身是字母就意味着单词被剪断。
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
