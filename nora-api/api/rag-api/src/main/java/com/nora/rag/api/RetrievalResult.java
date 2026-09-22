package com.nora.rag.api;

/**
 * 由 rag-service 返回的带分知识块(2026-09-22 知识库优化阶段 A 扩展)。
 *
 * <p><b>完整证据与预览分离:</b>此前只返回 500 字符截断的 {@code snippet},
 * 块后半段的内容虽然命中却无法交给模型。现在:
 * <ul>
 *   <li>{@code snippet} — 预览(列表展示用),围绕命中位置截取;</li>
 *   <li>{@code content} — 完整块正文(模型证据),对话注入用这个;</li>
 *   <li>{@code chunkId} — 稳定块标识(knowledge_chunk.id,重新分段前的定位)。</li>
 * </ul>
 *
 * <p><b>分数语义:</b>{@code score} 是展示用原始分(命中通道中的较高者),
 * 不代表回答可信度百分比;{@code vectorScore}/{@code keywordScore} 为
 * 双通道原始分(未命中为 null);{@code matchChannel} 说明命中来源
 * (vector/keyword/both)。融合排序由数组顺序表达(RRF 分不可比,不下发)。
 *
 * @param docId        knowledge_doc 行 id(融合排名中的块身份;文档名不唯一)
 * @param docName      被索引文档名
 * @param chunkIndex   块在文档内的 0 起位置
 * @param chunkId      knowledge_chunk.id(稳定块标识;旧数据可能为 null)
 * @param score        展示用原始分(越大越好;向量=余弦,关键词=trigram)
 * @param vectorScore  向量通道原始分;null = 该通道未命中
 * @param keywordScore 关键词通道原始分;null = 该通道未命中
 * @param matchChannel 命中通道:vector / keyword / both
 * @param snippet      预览摘录(列表展示;围绕命中位置截取)
 * @param content      完整块正文(模型证据)
 * @param source       原文档的存储来源路径/URI
 */
public record RetrievalResult(
        long docId,
        String docName,
        int chunkIndex,
        Long chunkId,
        double score,
        Double vectorScore,
        Double keywordScore,
        String matchChannel,
        String snippet,
        String content,
        String source
) {

    /** 兼容构造(旧调用点/测试):无通道细分,按分数来源推断。 */
    public RetrievalResult(long docId, String docName, int chunkIndex,
                           double score, String snippet, String source) {
        this(docId, docName, chunkIndex, null, score, null, null, null, snippet, snippet, source);
    }
}
