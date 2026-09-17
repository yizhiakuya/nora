package com.nora.agent.service;

import java.util.List;

import com.nora.agent.config.LlmProperties;

/**
 * 模型/渠道解析器(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):
 * 请求级模型名 > provider store(设置中心) > 静态 nora.llm.* 兜底;
 * 思考等级合并:请求级 > 设置页 per-model 默认 > auto(白名单约束)。
 */
class ModelResolver {

    private final LlmProperties llmProperties;
    private final ModelProviderService modelProviderService;

    ModelResolver(LlmProperties llmProperties, ModelProviderService modelProviderService) {
        this.llmProperties = llmProperties;
        this.modelProviderService = modelProviderService;
    }

    ResolvedLlm resolve(String requestedModel) {
        return resolve(requestedModel, null, null);
    }

    /**
     * 解析执行端点与思考等级:请求级等级 > 设置页该模型默认等级 > auto。
     * 该模型的 reasoningLevels 白名单同时约束请求级取值(不在白名单内则回落默认)。
     *
     * @param providerId 前端选定的渠道 id(同名模型跨渠道时精确定位);null = 按模型名解析
     */
    ResolvedLlm resolve(String requestedModel, String requestedReasoningLevel, Long providerId) {
        // 设置中心(数据库 provider store)优先:模型选择/思考等级/每模型协议都源于此。
        // 静态 nora.llm.* 配置仅作兜底(全新部署还没配 provider 时可用),
        // 否则环境变量一存在就会短路整个 provider 体系——UI 上怎么选模型都不生效。
        if (modelProviderService != null) {
            ResolvedLlm fromStore = resolveFromStore(requestedModel, requestedReasoningLevel, providerId);
            if (fromStore != null) return fromStore;
        }
        if (llmProperties.configured()) {
            return new ResolvedLlm(llmProperties.baseUrl(), llmProperties.apiKey(), llmProperties.model(), "openai",
                    null, null, null);
        }
        return null;
    }

    /** Provider-store leg of {@link #resolveLlm}; providerId 优先,缺失/失效时按模型名回落。 */
    private ResolvedLlm resolveFromStore(String requestedModel, String requestedReasoningLevel, Long providerId) {
        ModelProviderService.ActiveProvider provider = modelProviderService.activeProvider(providerId, requestedModel);
        if (provider == null || provider.endpoint() == null || provider.endpoint().isBlank()) return null;
        // 请求级模型名优先(activeProvider 已按它筛选供应商);仅在请求未指定时回落
        // 到该供应商模型列表的第一个。此前固定取 models.get(0),导致对话框里选的
        // 模型被静默替换成供应商第一个模型(如选 nemotron 实际跑 muse)。
        String model = requestedModel != null && !requestedModel.isBlank()
                && (provider.models() == null || provider.models().contains(requestedModel))
                ? requestedModel
                : (provider.models() == null || provider.models().isEmpty()
                        ? LlmProperties.DEFAULT_MODEL : provider.models().get(0));
        String effectiveLevel = effectiveReasoningLevel(provider, model, requestedReasoningLevel);
        // 协议按模型覆盖:modelSettings[model].protocol 优先,否则继承 provider 级协议
        ModelProviderService.PerModelSettings perModel =
                ModelProviderService.parseModelSettingsStatic(provider.modelSettingsJson()).forModel(model);
        String protocol = perModel.protocol() != null && !perModel.protocol().isBlank()
                ? perModel.protocol() : provider.protocol();
        return new ResolvedLlm(provider.endpoint(), provider.apiKey(), model, protocol, effectiveLevel,
                perModel.contextWindow(), perModel.vision());
    }

    private String effectiveReasoningLevel(ModelProviderService.ActiveProvider provider, String model,
                                           String requestedLevel) {
        ModelProviderService.PerModelSettings settings =
                ModelProviderService.parseModelSettingsStatic(provider.modelSettingsJson()).forModel(model);
        List<String> whitelist = settings.reasoningLevels();
        boolean hasRequested = requestedLevel != null && !requestedLevel.isBlank() && !"auto".equalsIgnoreCase(requestedLevel);
        if (hasRequested && (whitelist.isEmpty() || whitelist.contains(requestedLevel))) {
            return requestedLevel;
        }
        String configured = settings.defaultReasoningLevel();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)
                && (whitelist.isEmpty() || whitelist.contains(configured))) {
            return configured;
        }
        return null;
    }
}
