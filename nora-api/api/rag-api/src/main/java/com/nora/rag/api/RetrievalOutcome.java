package com.nora.rag.api;

/**
 * 一次检索的完整结果(2026-09-22,知识库优化阶段 A)。
 *
 * <p>为什么要状态而不是「空列表」:此前检索异常被折叠为空列表,单个依赖
 * 故障(嵌入不可用)表现为「资料里没有」——用户无法区分「真没有」与
 * 「检索坏了」。现在显式区分:
 * <ul>
 *   <li>{@code ok} — 通道正常且有命中;</li>
 *   <li>{@code no_match} — 通道正常但无命中过阈;</li>
 *   <li>{@code degraded} — 部分通道失败(如嵌入不可用但关键词正常);</li>
 *   <li>{@code unavailable} — 全部通道失败,必须显示「检索暂不可用」。</li>
 * </ul>
 *
 * @param status  见上;ok/no_match/degraded/unavailable
 * @param results 融合后的命中(最优在前);unavailable 时为空
 * @param vector  向量通道状态
 * @param keyword 关键词通道状态
 */
public record RetrievalOutcome(
        String status,
        java.util.List<RetrievalResult> results,
        ChannelStatus vector,
        ChannelStatus keyword
) {

    /** 单通道状态:{@code ok=false} 时 error 为可读原因。 */
    public record ChannelStatus(boolean ok, String error) {
        public static ChannelStatus up() {
            return new ChannelStatus(true, null);
        }

        public static ChannelStatus down(String error) {
            return new ChannelStatus(false, error);
        }
    }

    /** 判定整体状态(ok/no_match/degraded/unavailable)。 */
    public static String statusOf(java.util.List<RetrievalResult> results,
                                  ChannelStatus vector, ChannelStatus keyword) {
        boolean anyOk = vector.ok() || keyword.ok();
        boolean allFailed = !vector.ok() && !keyword.ok();
        if (allFailed) {
            return "unavailable";
        }
        boolean anyFailed = !vector.ok() || !keyword.ok();
        if (results != null && !results.isEmpty()) {
            return anyFailed ? "degraded" : "ok";
        }
        return anyFailed ? "degraded" : "no_match";
    }
}
