package com.nora.rag.api;

/**
 * 由 {@link RagService#search(SearchRequest)} 返回的带分知识块。
 *
 * @param docId      knowledge_doc 行 id(融合排名中的块身份;文档名不唯一——
 *                   两个文件可能同名)
 * @param docName    被索引文档名
 * @param chunkIndex 块在文档内的 0 起位置
 * @param score      相关度分(越大越好)
 * @param snippet    块的文本摘录,已裁剪以适合提示词注入
 * @param source     原文档的存储来源路径/URI
 */
public record RetrievalResult(
        long docId,
        String docName,
        int chunkIndex,
        double score,
        String snippet,
        String source
) {
}
