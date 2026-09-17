package com.nora.agent.api;

/**
 * 附在 agent 回答上的检索引用。
 *
 * <p>由 RAG 工具在 ReAct 循环中产出(architecture-v2.md 4.6 节):
 * agent 标注回答基于哪个文档块,前端可在消息旁渲染出处。</p>
 *
 * @param docName    人类可读文档名(如 {@code arch-notes.md})
 * @param source     文档的存储位置或 URI
 * @param chunkIndex 被引块在文档内的 0 起下标
 * @param score      检索相似度分 [0, 1]
 * @param snippet    被引块的短摘录
 */
public record Citation(
        String docName,
        String source,
        int chunkIndex,
        double score,
        String snippet) {
}
