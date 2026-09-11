package com.nora.rag.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagApiRecordsTest {

    @Test
    void recordRoundtripKeepsAllFields() {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");

        SearchRequest request = new SearchRequest("how to tune pgvector", 8);
        assertEquals("how to tune pgvector", request.query());
        assertEquals(8, request.topK());

        RetrievalResult result = new RetrievalResult(
                9L, "pgvector-guide.pdf", 3, 0.87, "HNSW index ...", "files/2026/09/pgvector-guide.pdf");
        assertEquals(9L, result.docId());
        assertEquals("pgvector-guide.pdf", result.docName());
        assertEquals(3, result.chunkIndex());
        assertEquals(0.87, result.score());
        assertEquals("HNSW index ...", result.snippet());
        assertEquals("files/2026/09/pgvector-guide.pdf", result.source());

        IndexStatistics stats = new IndexStatistics(42, 1180, "text-embedding-3-small", now);
        assertEquals(42, stats.docCount());
        assertEquals(1180, stats.chunkCount());
        assertEquals("text-embedding-3-small", stats.embeddingModel());
        assertEquals(now, stats.indexedAt());
    }
}
