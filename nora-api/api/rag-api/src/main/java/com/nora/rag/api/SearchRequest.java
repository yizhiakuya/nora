package com.nora.rag.api;

/**
 * {@link RagService#search(SearchRequest)} 的检索参数。
 *
 * @param query 自然语言查询文本
 * @param topK  最大返回结果数
 */
public record SearchRequest(
        String query,
        int topK
) {
}
