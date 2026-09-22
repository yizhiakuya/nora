package com.nora.agent.dto;

/**
 * SSE {@code sources} 事件载荷条目,匹配前端 Citation 契约(types/index.ts)。
 *
 * <p><b>2026-09-22(知识库优化阶段 A)扩展:</b>
 * <ul>
 *   <li>{@code chunkId} — 稳定块标识(knowledge_chunk.id);此前只有
 *       「文件名+块序号」,重新分段后同一序号指向不同内容;</li>
 *   <li>{@code content} — 完整块正文(模型证据);注入系统提示用这个,
 *       {@code snippet} 只作列表预览。此前只注入 500 字符截断的 snippet,
 *       答案在块后半段时「命中却无法回答」;</li>
 *   <li>{@code matchChannel}/{@code vectorScore}/{@code keywordScore} —
 *       命中来源与双通道原始分;不再用单一百分比充当可信度。</li>
 * </ul>
 *
 * @param docId        knowledge_doc id(去重身份;名称不唯一)
 * @param docName      文档名
 * @param source       存储来源(file/database/repo/…)
 * @param chunkIndex   块在文档内的位置
 * @param score        检索相似度分(展示用原始分,非可信度百分比)
 * @param snippet      预览摘录(列表展示)
 * @param chunkId      稳定块 id;旧数据/用户引用为 null
 * @param matchChannel 命中通道:vector/keyword/both;用户引用为 null
 * @param vectorScore  向量通道原始分;null = 未命中该通道
 * @param keywordScore 关键词通道原始分;null = 未命中该通道
 * @param content      完整块正文(模型证据);仅 RAG 命中携带
 */
public record CitationDto(
        Long docId,
        String docName,
        String source,
        int chunkIndex,
        double score,
        String snippet,
        Long chunkId,
        String matchChannel,
        Double vectorScore,
        Double keywordScore,
        String content
) {

    /** 兼容构造(用户引用/技能/旧调用点):无通道与稳定标识。 */
    public CitationDto(Long docId, String docName, String source, int chunkIndex,
                       double score, String snippet) {
        this(docId, docName, source, chunkIndex, score, snippet,
                null, null, null, null, null);
    }

    /** 注入用证据正文:完整内容缺失时退回预览。 */
    public String evidence() {
        return content != null && !content.isBlank() ? content : snippet;
    }
}
