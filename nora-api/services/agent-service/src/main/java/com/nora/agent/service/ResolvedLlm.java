package com.nora.agent.service;

/**
 * 已解析的执行端点与模型配置(2026-09-17 从 ChatOrchestrationService 拆出)。
 * Package-visible for tests; never returned outside the service.
 */
record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,
                   /** 生效思考等级(已合并请求级与设置页默认);null = auto */
                   String effectiveReasoningLevel,
                   /** 模型上下文窗口(tokens);null = 未配置 */
                   Long contextWindow,
                   /**
                    * 识图能力(设置页每模型开关):TRUE/FALSE = 强制;null = 运行时自适应
                    * (默认尝试附加图片,上游以"不支持图片"拒绝时自动剥离并记忆)。
                    */
                   Boolean vision) {

    /** 返回携带指定思考等级的副本(null 安全:llm 为 null 时返回 null)。 */
    static ResolvedLlm withLevel(ResolvedLlm llm, String reasoningLevel) {
        if (llm == null) return null;
        return new ResolvedLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.protocol(), reasoningLevel,
                llm.contextWindow(), llm.vision());
    }
}
