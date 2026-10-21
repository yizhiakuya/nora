package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.TaskContext;
import com.nora.rag.service.RetrievalService;

/**
 * 一轮对话的检索阶段：知识库召回 + 消息引用注入，并如实下发降级状态。
 *
 * <p>从 {@link ChatOrchestrationService#chat} 拆出——检索有自己的一组判断
 * （是否检索、检索范围、引用与召回的优先序、通道降级如何展示），
 * 与模型循环互不影响。这里只产出结果和步骤，不碰会话与模型。
 */
@Component
class TurnRetrieval {

    /** 语义召回条数：引用之外的补充片段，够用即可，避免挤占上下文预算。 */
    private static final int TOP_K = 6;

    private final KnowledgeRetrieval knowledgeRetrieval;
    private final MessageRefResolver messageRefResolver;

    TurnRetrieval(KnowledgeRetrieval knowledgeRetrieval, MessageRefResolver messageRefResolver) {
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.messageRefResolver = messageRefResolver;
    }

    /**
     * 执行检索并下发步骤。
     *
     * <p>顺序是刻意的：用户明确引用的内容排在语义召回之前，且不受相似度下限影响
     * ——引用是确定性输入，不是猜测。检索通道故障（degraded / unavailable）
     * 显式成为一条步骤，避免把「检索坏了」呈现成「资料里没有」。
     */
    RetrievalOutcome retrieve(String userMessage, TaskContext taskContext,
                              ChatOrchestrationService.ChatEventConsumer eventConsumer) {
        long startedAt = System.currentTimeMillis();
        RetrievalService.RetrievalScope scope = scopeOf(taskContext);
        boolean knowledgeInput = scope != null || (taskContext != null && (
                taskContext.origin() != null && "knowledge".equals(taskContext.origin().kind())
                        || taskContext.refs() != null && taskContext.refs().stream().anyMatch(ref -> "doc".equals(ref.kind()))))
                || MessageRefResolver.parse(userMessage).stream().anyMatch(ref -> "doc".equals(ref.kind()));
        KnowledgeRetrieval.RetrievalPayload retrieval = knowledgeInput
                ? knowledgeRetrieval.searchWithStatus(userMessage, TOP_K, scope)
                : new KnowledgeRetrieval.RetrievalPayload("skipped", List.of(), null, null);
        List<CitationDto> citations = new ArrayList<>(retrieval.resultsOrEmpty());

        List<MessageRefResolver.Ref> messageRefs = MessageRefResolver.parse(userMessage);
        var references = messageRefResolver.resolveContext(taskContext, messageRefs);
        List<CitationDto> refCitations = references.citations();
        int refCount = taskContext != null && taskContext.refs() != null
                ? Math.max(taskContext.refs().size(), messageRefs.size())
                : messageRefs.size();
        if (!refCitations.isEmpty()) {
            List<CitationDto> merged = new ArrayList<>(refCitations);
            merged.addAll(citations);
            citations = merged;
        }
        long retrievalMs = System.currentTimeMillis() - startedAt;

        if (retrieval.unavailable() || retrieval.degraded()) {
            String label = retrieval.unavailable() ? "检索暂不可用" : "检索降级(部分通道失败)";
            eventConsumer.step(new com.nora.agent.dto.ChatStepDto(
                    "s-rag-degraded", "tool", label,
                    retrieval.degradedReason(),
                    retrievalMs, retrieval.unavailable() ? "failed" : "completed", null, null, null, 0));
        }

        if (!citations.isEmpty()) {
            String summary = refCitations.isEmpty()
                    ? "召回 " + citations.size() + " 个相关片段（" + citations.get(0).docName() + " 等）"
                    : "引用 " + refCount + " 项 + 召回 "
                            + (citations.size() - refCitations.size()) + " 个相关片段";
            eventConsumer.step(new com.nora.agent.dto.ChatStepDto(
                    "s-rag", "tool", "检索知识库", summary,
                    retrievalMs, "completed", null, null, null, 0));
            eventConsumer.sources(citations);
        }
        return new RetrievalOutcome(citations, references, scope);
    }

    /**
     * 从 TaskContext 提取检索范围（B4）：用户显式限定时只从指定库/文档召回；
     * 未限定（字段缺失/全空）返回 null，保持原有全局检索行为。
     */
    static RetrievalService.RetrievalScope scopeOf(TaskContext taskContext) {
        if (taskContext == null || taskContext.retrievalScope() == null) {
            return null;
        }
        TaskContext.RetrievalScope s = taskContext.retrievalScope();
        if (s.isEmpty()) {
            return null;
        }
        return new RetrievalService.RetrievalScope(s.baseId(), s.docIds(), s.sources(), null, null);
    }

    /** 检索结果：合并后的引用、引用解析产物（含图片）与生效范围。 */
    record RetrievalOutcome(List<CitationDto> citations, MessageRefResolver.ResolvedContext references,
                            RetrievalService.RetrievalScope scope) {
    }
}
