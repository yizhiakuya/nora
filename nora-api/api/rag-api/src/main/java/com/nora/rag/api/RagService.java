package com.nora.rag.api;

import java.util.List;

/**
 * 知识检索的 Dubbo 契约,由 rag-service 提供、agent-service 消费
 * (architecture-v2.md 5.1 节)。
 *
 * <p>实现注册到 Nacos;消费方经 {@code @DubboReference} 注入。
 */
public interface RagService {

    /**
     * 对知识索引做语义检索。
     *
     * @param request 查询文本与结果条数
     * @return 按相关度排序的带分块(最优在前)
     */
    List<RetrievalResult> search(SearchRequest request);

    /**
     * 当前索引统计快照。
     *
     * @return 文档/分块计数及所用嵌入模型
     */
    IndexStatistics getIndexStats();
}
