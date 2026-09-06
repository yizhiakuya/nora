import { create } from "zustand";
import { persist } from "zustand/middleware";
import { modelsApi } from "@/lib/services/modelsApi";
import { USE_BACKEND } from "@/lib/api/client";

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

/** 单个模型的请求参数配置:上下文窗口 + 思考等级白名单 + 默认等级 + 协议覆盖 */
export interface PerModelSettings {
  contextWindow?: number | null;
  /** 该模型可选的思考等级;空 = 全部可用等级 */
  reasoningLevels?: string[];
  /** 默认等级;null/auto = 自动 */
  defaultReasoningLevel?: string | null;
  /** 该模型的协议覆盖;null = 继承服务商协议 */
  protocol?: ProviderProtocol | null;
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
  /** 后端模式:拉取服务端 provider 列表 */
  syncFromBackend: () => Promise<void>;
  addProvider: (p: { name: string; url: string; key: string; protocol?: ProviderProtocol; models?: string[]; modelSettings?: Record<string, { protocol?: ProviderProtocol }> }) => void;
  /** 编辑服务商基础信息;key 留空 = 保持原密钥 */
  editProvider: (id: number, patch: { name: string; url: string; key?: string; protocol: ProviderProtocol }) => void;
  removeProvider: (id: number) => void;
  toggleEnabled: (id: number) => void;
  setDefaultModel: (m: string) => void;
  markStatus: (id: number, status: ModelProvider["status"]) => void;
  /** 更新单个模型的设置(上下文窗口/思考等级);merge 语义 */
  updateModelSettings: (id: number, model: string, patch: Partial<PerModelSettings>) => void;
  /** 真实连通测试(后端模式走 /test,Mock 模式由调用方自行模拟) */
  testProvider: (id: number) => Promise<"ok" | "fail">;
}

/** 本地新增(后端不可用或 Mock 模式的回退路径) */
function localProvider(input: { name: string; url: string; key: string; protocol?: ProviderProtocol; models?: string[] }): ModelProvider {
  return {
    id: Date.now(),
    name: input.name.trim(),
    url: input.url.trim(),
    masked: input.key.slice(0, 4) + "••••••••" + input.key.slice(-4),
    enabled: true,
    status: "untested",
    protocol: input.protocol ?? "openai",
    models: input.models?.length ? input.models : ["默认模型"],
  };
}

/**
 * 模型服务商接入唯一数据源：模型管理页与对话页模型选择器共享。
 * 完整接入 = 名称 + 端点 URL + 密钥。
 * USE_BACKEND 时 CRUD 与连通测试走 agent-service /api/models/providers。
 */
export const useModelProviders = create<ModelProvidersState>()(
  persist(
    (set, get) => ({
      providers: [],
      defaultModel: "未配置",
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const providers = await modelsApi.listProviders();
          // 后端为准:有数据时整体替换本地
          if (providers.length > 0) {
            set((state) => {
              const defaultStillThere = providers.some((p) => p.models.includes(state.defaultModel));
              return {
                providers,
                defaultModel: defaultStillThere
                  ? state.defaultModel
                  : providers.find((p) => p.enabled)?.models[0] ?? state.defaultModel,
              };
            });
          }
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addProvider: ({ name, url, key, protocol = "openai", models, modelSettings }) => {
        const optimistic = localProvider({ name, url, key, protocol, models });
        if (modelSettings) optimistic.modelSettings = { ...optimistic.modelSettings, ...modelSettings };
        set((state) => ({ providers: [...state.providers, optimistic] }));
        if (USE_BACKEND) {
          modelsApi.createProvider({ name: name.trim(), protocol, endpoint: url.trim(), apiKey: key, models, ...(modelSettings ? { modelSettings: modelSettings as never } : {}) })
            .then((saved) => {
              set((state) => ({
                providers: state.providers.map((p) => (p.id === optimistic.id ? saved : p)),
              }));
            })
            .catch(() => { /* 保留本地乐观条目,错误由调用方 toast */ });
        }
      },
      editProvider: (id, { name, url, key, protocol }) => {
        set((state) => ({
          providers: state.providers.map((p) =>
            p.id === id
              ? {
                  ...p,
                  name: name.trim(),
                  url: url.trim(),
                  protocol,
                  masked: key ? key.slice(0, 4) + "••••••••" + key.slice(-4) : p.masked,
                  status: "untested" as const,
                }
              : p
          ),
        }));
        if (USE_BACKEND && id < 1e12) {
          modelsApi.updateProvider(id, {
            name: name.trim(),
            protocol,
            endpoint: url.trim(),
            ...(key ? { apiKey: key } : {}),
          }).catch(() => { /* 乐观更新已生效 */ });
        }
      },
      removeProvider: (id) => {
        const { providers, defaultModel } = get();
        const target = providers.find((p) => p.id === id);
        const next = providers.filter((p) => p.id !== id);
        const patch: Partial<ModelProvidersState> = { providers: next };
        if (target?.models.includes(defaultModel)) {
          patch.defaultModel = next.find((p) => p.enabled)?.models[0] ?? "未配置";
        }
        set(patch as ModelProvidersState);
        if (USE_BACKEND && id < 1e12) {
          // 服务端 id(BIGSERIAL 小值)才发删除;本地 Date.now() 乐观条目跳过
          modelsApi.deleteProvider(id).catch(() => { /* 本地已删,服务端失败不打断 */ });
        }
      },
      toggleEnabled: (id) => {
        const target = get().providers.find((p) => p.id === id);
        set((state) => ({
          providers: state.providers.map((p) => (p.id === id ? { ...p, enabled: !p.enabled } : p)),
        }));
        if (USE_BACKEND && target) {
          modelsApi.updateProvider(id, { enabled: !target.enabled }).catch(() => { /* 乐观更新已生效 */ });
        }
      },
      setDefaultModel: (m) => set({ defaultModel: m }),
      updateModelSettings: (id, model, patch) => {
        set((state) => ({
          providers: state.providers.map((p) => {
            if (p.id !== id) return p;
            const merged: ModelSettings = { ...(p.modelSettings ?? {}) };
            merged[model] = { ...(merged[model] ?? {}), ...patch };
            return { ...p, modelSettings: merged };
          }),
        }));
        if (USE_BACKEND && id < 1e12) {
          // 后端以 provider 为粒度整体保存 modelSettings;从最新状态取
          const current = get().providers.find((p) => p.id === id);
          if (current?.modelSettings) {
            modelsApi.updateProvider(id, { modelSettings: current.modelSettings })
              .catch(() => { /* 乐观更新已生效 */ });
          }
        }
      },
      markStatus: (id, status) =>
        set((state) => ({
          providers: state.providers.map((p) => (p.id === id ? { ...p, status } : p)),
        })),
      testProvider: async (id) => {
        if (!USE_BACKEND) {
          throw new Error("mock mode: caller simulates the test");
        }
        // 测试成功时后端会自动用上游 /v1/models 覆盖模型列表,这里取回刷新后的 provider
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
