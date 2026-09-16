package com.nora.agent.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型能力降级注册表(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):
 * 进程内记忆「上游拒绝图片 / 拒绝 reasoning_effort」的模型,避免每轮都"失败→重试"白费。
 * 键 = 端点|模型(effort 键额外含档位);重启后重新探测。
 */
class ModelCapabilityRegistry {

    private final Set<String> visionRejectedModels = ConcurrentHashMap.newKeySet();
    private final Set<String> effortRejectedModels = ConcurrentHashMap.newKeySet();

    /** 探测记忆的键:同一上游端点上的同名模型共享结论。 */
    static String visionKey(ResolvedLlm llm) {
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model());
    }

    /** 探测记忆的键:上游端点 + 模型 + 档位(stripLevel=true 时按"无档位"计算)。 */
    static String effortKey(ResolvedLlm llm, boolean stripLevel) {
        String level = stripLevel || llm.effectiveReasoningLevel() == null
                ? "" : llm.effectiveReasoningLevel().toLowerCase();
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model())
                + "|" + level;
    }

    /**
     * 是否允许把图片附到该模型的请求里。
     * 优先级:设置页显式开关 > 运行时探测记忆 > 默认尝试(上游拒绝则自动剥离)。
     */
    boolean visionAllowed(ResolvedLlm llm, int imageCount) {
        if (llm == null || imageCount <= 0) return false;
        if (Boolean.FALSE.equals(llm.vision())) return false;
        if (Boolean.TRUE.equals(llm.vision())) return true;
        return !visionRejectedModels.contains(visionKey(llm));
    }

    /** 记忆"上游拒绝图片"的模型:首次遇到拒绝后,后续轮次直接不再附加图片。 */
    void markVisionRejected(ResolvedLlm llm) {
        visionRejectedModels.add(visionKey(llm));
    }

    /** 该(端点|模型|档位)是否已被上游拒绝过。 */
    boolean isEffortRejected(ResolvedLlm llm) {
        return effortRejectedModels.contains(effortKey(llm, false));
    }

    /** 挂"该档位被拒"结论(重试成功才保留)。 */
    void markEffortRejected(ResolvedLlm llm) {
        effortRejectedModels.add(effortKey(llm, false));
    }

    /** 挂"该模型不注入档位"结论:让重试真正不带 reasoning_effort。 */
    void markEffortStripped(ResolvedLlm llm) {
        effortRejectedModels.add(effortKey(llm, true));
    }

    void clearEffortRejected(ResolvedLlm llm) {
        effortRejectedModels.remove(effortKey(llm, false));
    }

    void clearEffortStripped(ResolvedLlm llm) {
        effortRejectedModels.remove(effortKey(llm, true));
    }

    /**
     * 上游是否明确拒绝 reasoning_effort 取值。
     * 实测文案(中转站透传上游错误):
     * "the reasoning effort value is not supported by the current model" /
     * "invalid_reasoning_effort" / "unsupported value ... reasoning_effort"
     */
    static boolean isReasoningEffortUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        if (!e.contains("reasoning")) return false;
        return e.contains("not supported") || e.contains("unsupported")
                || e.contains("invalid") || e.contains("not valid");
    }

    /**
     * 上游错误是否为"不支持图像输入"。
     * 实测拒绝文案示例:
     * "Model X does not support image input. Remove the image content or use a vision-capable model."
     */
    static boolean isVisionUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        return e.contains("does not support image") || e.contains("not support image")
                || e.contains("vision-capable") || e.contains("image input")
                || e.contains("invalid image") || e.contains("unsupported image");
    }

    /**
     * 中转以流式返回时会把上游 400 粒度化为 "Upstream error: 400"——
     * 看不到"不支持图像输入"原文。因此当请求里带图且收到 400 时,
     * 按"可能是识图不支持"处理(剥图重试一次,成功则记住结论)。
     */
    static boolean isGenericUpstream400(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        boolean has400 = e.contains("400");
        boolean vague = e.contains("upstream error") || e.contains("上游");
        return has400 && vague;
    }
}
