package com.nora.rag.api;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class RagApiRecordsTest {

    @Test
    void recordRoundtripKeepsAllFields() {
        RetrievalResult result = new RetrievalResult(
                9L, "pgvector-guide.pdf", 3, 0.87, "HNSW index ...", "files/2026/09/pgvector-guide.pdf");
        result.docId();
        result.docName();
        result.chunkIndex();
        result.score();
        result.snippet();
        result.source();

    }
}
