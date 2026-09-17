package com.nora.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 按字符近似分块(每块约 2000 字符,重叠 200 字符),对中英混排友好:
 * 无分词器依赖,尽量在空白处切分,不把英文单词拦腰剪断。
 */
@Service
public class ChunkingService {

    /** 每块目标字符数。 */
    static final int CHUNK_SIZE = 2000;
    /** 相邻块共享的字符数。 */
    static final int OVERLAP = 200;

    /**
     * 把文本切成带重叠的块。
     *
     * @param text 原始文档文本
     * @return 按文档序的块;空白输入为空列表
     */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.strip();
        int length = normalized.length();
        int step = CHUNK_SIZE - OVERLAP;
        int start = 0;

        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            // 非末块回退到最后一个空白处,不让英文单词被剪半;至少保留半块。
            if (end < length) {
                int spaceBoundary = lastWhitespace(normalized, end);
                if (spaceBoundary > start + CHUNK_SIZE / 2) {
                    end = spaceBoundary;
                }
            }
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= length) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }

        return chunks;
    }

    private int lastWhitespace(String text, int from) {
        for (int i = from; i > from - OVERLAP && i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }
}
