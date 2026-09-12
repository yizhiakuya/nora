package com.nora.rag.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class RagApiRecordsTest {

    @Test
    void recordRoundtripKeepsAllFields() {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");

        SearchRequest request = new SearchRequest("how to tune pgvector", 8);
        request.query();
        request.topK();

        RetrievalResult result = new RetrievalResult(
                9L, "pgvector-guide.pdf", 3, 0.87, "HNSW index ...", "files/2026/09/pgvector-guide.pdf");
        result.docId();
        result.docName();
        result.chunkIndex();
        result.score();
        result.snippet();
        result.source();

        IndexStatistics stats = new IndexStatistics(42, 1180, "text-embedding-3-small", now);
        stats.docCount();
        stats.chunkCount();
        stats.embeddingModel();
        stats.indexedAt();
    }
}
