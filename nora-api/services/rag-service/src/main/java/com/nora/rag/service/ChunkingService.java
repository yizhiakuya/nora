package com.nora.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Character-approximate chunking (~2000 chars per chunk, 200 chars overlap),
 * friendly to mixed Chinese/English text: no tokenizer dependency, splits on
 * whitespace where possible so chunks do not shear English words apart.
 */
@Service
public class ChunkingService {

    /** Target characters per chunk. */
    static final int CHUNK_SIZE = 2000;
    /** Characters shared between adjacent chunks. */
    static final int OVERLAP = 200;

    /**
     * Splits text into overlapping chunks.
     *
     * @param text raw document text
     * @return chunks in document order; empty list for blank input
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
            // For non-final chunks, back off to the last whitespace so English
            // words are not sheared in half; keep at least half a chunk.
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
