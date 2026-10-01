package com.nora.agent.service;

import java.util.List;

/**
 * 模型/渠道解析器(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):
 * 请求级模型名 > provider store(设置中心,数据库为唯一配置来源);
 * 思考等级合并:请求级 > 设置页 per-model 默认 > auto(白名单约束)。
 *
 * <p>2026-10-01:移除静态 nora.llm.* 环境变量兜底(用户明确)——配置只能来自
 * 设置中心(数据库)。此前 .env 里有 key 时会绕过 provider 体系直接生效,
 * 造成「UI 显示未配置、对话却能跑」的不一致;且默认值指向开发者内网地址,
 * 对任何其他部署者都是无意义的隐形行为。未配置 provider 时 resolve 返回 null,
 * 由调用方给出「请到设置中心配置」的明确指引。
 */
class ModelResolver {

    private final ModelProviderService modelProviderService;

    ModelResolver(ModelProviderService modelProviderService) {
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
     * @return 解析结果;未配置任何可用 provider 时返回 null
     */
    ResolvedLlm resolve(String requestedModel, String requestedReasoningLevel, Long providerId) {
        if (modelProviderService == null) {
            return null;
        }
        return resolveFromStore(requestedModel, requestedReasoningLevel, providerId);
    }

    /** Provider-store leg of {@link #resolve}; providerId 优先,缺失/失效时按模型名回落。 */
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
                        ? null : provider.models().get(0));
        if (model == null) return null;
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
