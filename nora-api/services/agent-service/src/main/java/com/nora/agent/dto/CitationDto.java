package com.nora.agent.dto;

/**
 * SSE {@code sources} 事件载荷条目,匹配前端 Citation 契约(types/index.ts)。
 *
 * @param docId      knowledge_doc id(去重身份;名称不唯一)
 * @param docName    文档名
 * @param source     存储来源(file/database/repo/…)
 * @param chunkIndex 块在文档内的位置
 * @param score      检索相似度分
 * @param snippet    被引块的文本摘录
 */
public record CitationDto(
        Long docId,
        String docName,
        String source,
        int chunkIndex,
        double score,
        String snippet
) {
}
