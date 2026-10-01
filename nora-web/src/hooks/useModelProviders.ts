import { create } from "zustand";
import { persist } from "zustand/middleware";
import { modelsApi } from "@/lib/services/modelsApi";

export type ProviderProtocol = "openai" | "responses" | "ollama" | "anthropic";

/** 思考等级可选项;ultra 仅展示(上游暂未开放,禁选) */
export const REASONING_LEVELS = ["none", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export const REASONING_LEVELS_DISABLED = ["ultra"] as const;

export const PROTOCOL_META: Record<ProviderProtocol, { label: string; desc: string }> = {
  openai:    { label: "Chat Completions", desc: "标准 /v1/chat/completions 协议，适用于 OpenAI、DeepSeek、中转站等" },
  responses: { label: "OpenAI Responses", desc: "OpenAI 新版 /v1/responses 协议（GPT-5/o 系列官方端点）" },
  anthropic: { label: "Anthropic",      desc: "Anthropic Messages API（/v1/messages）" },
  ollama:    { label: "Ollama",          desc: "本地 Ollama 原生协议（/api/chat）" },
};

/** 单个模型的请求参数配置:上下文窗口 + 思考等级白名单 + 默认等级 + 协议覆盖 + 识图 */
export interface PerModelSettings {
  contextWindow?: number | null;
  /** 该模型可选的思考等级;空 = 全部可用等级 */
  reasoningLevels?: string[];
  /** 默认等级;null/auto = 自动 */
  defaultReasoningLevel?: string | null;
  /** 该模型的协议覆盖;null = 继承服务商协议 */
  protocol?: ProviderProtocol | null;
  /**
   * 识图能力：true/false = 显式开关；null/未设置 = 自适应
   * （默认尝试附图，上游拒绝时自动跳过图片）
   */
  vision?: boolean | null;
}

/** 按模型 id 索引的设置表(后端 model_provider.model_settings JSONB) */
export type ModelSettings = Record<string, PerModelSettings>;

export interface ModelProvider {
  id: number;
  name: string;
  url: string;
  masked: string;
  enabled: boolean;
  models: string[];
  /** 测试连通状态 */
  status: "untested" | "ok" | "fail";
  /** API 协议类型 */
  protocol: ProviderProtocol;
  /** 按模型配置(上下文窗口/思考等级) */
  modelSettings?: ModelSettings;
}



interface ModelProvidersState {
  providers: ModelProvider[];
  defaultModel: string;
  /**
   * 默认模型所属渠道 id;null/undefined = 未指定(按模型名回落,兼容旧数据)。
   * 同名模型可同时存在于多个渠道,只有「模型名 + 渠道 id」才能唯一确定请求目标。
   */
  defaultProviderId?: number | null;
  /** 后端模式:拉取服务端 provider 列表 */
  syncFromBackend: () => Promise<void>;
  addProvider: (p: { name: string; url: string; key: string; protocol?: ProviderProtocol; models?: string[]; modelSettings?: ModelSettings }) => Promise<void>;
  /** 编辑服务商基础信息;key 留空 = 保持原密钥 */
  editProvider: (id: number, patch: { name: string; url: string; key?: string; protocol: ProviderProtocol; models?: string[]; modelSettings?: ModelSettings }) => Promise<void>;
  removeProvider: (id: number) => Promise<void>;
  toggleEnabled: (id: number) => Promise<void>;
  setDefaultModel: (m: string, providerId?: number | null) => void;
  markStatus: (id: number, status: ModelProvider["status"]) => void;
  /** 更新单个模型的设置(上下文窗口/思考等级);merge 语义 */
  updateModelSettings: (id: number, model: string, patch: Partial<PerModelSettings>) => Promise<void>;
  /** 真实连通测试(走 /test 端点) */
  testProvider: (id: number) => Promise<"ok" | "fail">;
}

/**
 * 解析默认模型实际生效的服务商(渠道):显式 id 优先(且启用、且仍提供该模型),
 * 缺失/失效时按模型名取第一个启用的——与后端 activeProvider(providerId, model)
 * 的回落顺序一致。渠道仍在但模型列表已不含该模型时,不静默改跑渠道首个模型,
 * 而是按模型名在其它渠道重新解析。
 */
export function resolveDefaultProvider(
  providers: ModelProvider[],
  defaultModel: string,
  defaultProviderId?: number | null
): ModelProvider | undefined {
  if (defaultProviderId != null) {
    const byId = providers.find(
      (p) => p.id === defaultProviderId && p.enabled && p.models.includes(defaultModel));
    if (byId) return byId;
  }
  return providers.find((p) => p.enabled && p.models.includes(defaultModel));
}

/**
 * 模型服务商接入唯一数据源：模型管理页与对话页模型选择器共享。
 * 完整接入 = 名称 + 端点 URL + 密钥。
 * CRUD 与连通测试走 agent-service /api/models/providers。
 */
export const useModelProviders = create<ModelProvidersState>()(
  persist(
    (set, get) => ({
      providers: [],
      defaultModel: "未配置",
      defaultProviderId: null,
      syncFromBackend: async () => {
        try {
          const providers = await modelsApi.listProviders();
          set((state) => {
            const defaultStillThere = resolveDefaultProvider(
              providers, state.defaultModel, state.defaultProviderId);
            if (defaultStillThere) {
              // 保留用户的显式渠道选择(渠道临时禁用/恢复后仍能回到原选择);
              // 仅旧数据(只有模型名、无渠道)时把它一次性钉到当前解析出的渠道
              return {
                providers,
                defaultProviderId: state.defaultProviderId ?? defaultStillThere.id,
              };
            }
            const fallback = providers.find((p) => p.enabled && p.models.length > 0);
            return {
              providers,
              defaultModel: fallback?.models[0] ?? "未配置",
              defaultProviderId: fallback?.id ?? null,
            };
          });
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addProvider: async ({ name, url, key, protocol = "openai", models, modelSettings }) => {
        const saved = await modelsApi.createProvider({
          name: name.trim(), protocol, endpoint: url.trim(), apiKey: key, models, modelSettings,
        });
        set((state) => ({ providers: [...state.providers, saved] }));
      },
      editProvider: async (id, { name, url, key, protocol, models, modelSettings }) => {
        const saved = await modelsApi.updateProvider(id, {
          name: name.trim(), protocol, endpoint: url.trim(), models, modelSettings,
          ...(key?.trim() ? { apiKey: key.trim() } : {}),
        });
        set((state) => ({ providers: state.providers.map((p) => p.id === id ? saved : p) }));
      },
      removeProvider: async (id) => {
        await modelsApi.deleteProvider(id);
        const { providers, defaultModel, defaultProviderId } = get();
        const next = providers.filter((p) => p.id !== id);
        const patch: Partial<ModelProvidersState> = { providers: next };
        // 删后重新解析默认模型的实际渠道:删的正是默认渠道(或默认模型已无可用渠道)
        // 时,重选到第一个启用渠道的首个模型;仅同名模型在其他渠道仍可解析则保留。
        const stillValid = resolveDefaultProvider(next, defaultModel,
            defaultProviderId === id ? null : defaultProviderId);
        if (!stillValid) {
          const fallback = next.find((p) => p.enabled && p.models.length > 0);
          patch.defaultModel = fallback?.models[0] ?? "未配置";
          patch.defaultProviderId = fallback?.id ?? null;
        } else if (defaultProviderId === id) {
          // 同名模型回落到了另一渠道:把默认渠道改钉到回落结果,保持唯一解析
          patch.defaultProviderId = stillValid.id;
        }
        set(patch);
      },
      toggleEnabled: async (id) => {
        const target = get().providers.find((p) => p.id === id);
        if (!target) throw new Error("服务商已不存在,请刷新列表");
        const saved = await modelsApi.updateProvider(id, { enabled: !target.enabled });
        set((state) => ({ providers: state.providers.map((p) => p.id === id ? saved : p) }));
      },
      setDefaultModel: (m, providerId) => set({
        defaultModel: m,
        // 未显式传渠道(旧调用点)时按模型名解析一次,尽量钉到具体渠道;
        // 找不到(未配置)时置 null,后端按模型名回落
        defaultProviderId: providerId !== undefined
          ? providerId
          : resolveDefaultProvider(get().providers, m, null)?.id ?? null,
      }),
      updateModelSettings: async (id, model, patch) => {
        const current = get().providers.find((p) => p.id === id);
        if (!current) throw new Error("服务商已不存在,请刷新列表");
        const modelSettings = {
          ...current.modelSettings,
          [model]: { ...current.modelSettings?.[model], ...patch },
        };
        const saved = await modelsApi.updateProvider(id, { modelSettings });
        set((state) => ({ providers: state.providers.map((p) => p.id === id ? saved : p) }));
      },
      markStatus: (id, status) =>
        set((state) => ({
          providers: state.providers.map((p) => (p.id === id ? { ...p, status } : p)),
        })),
      testProvider: async (id) => {
        // 测试只更新连通状态;刷新已保存配置,保留用户选择的模型。
        const result = await modelsApi.testAndRefresh(id, (updated) => {
          set((state) => ({
            providers: state.providers.map((p) => (p.id === id ? updated : p)),
          }));
        });
        set((state) => ({
          providers: state.providers.map((p) => (p.id === id ? { ...p, status: result.status } : p)),
        }));
        return result.status;
      },
    }),
    { name: "model-providers" }
  )
);
