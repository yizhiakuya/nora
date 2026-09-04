import { create } from "zustand";
import { persist } from "zustand/middleware";

export type ProviderProtocol = "openai" | "ollama" | "anthropic";

export const PROTOCOL_META: Record<ProviderProtocol, { label: string; desc: string }> = {
  openai:    { label: "OpenAI 兼容",  desc: "标准 /v1/chat/completions 协议，适用于 OpenAI、DeepSeek、中转站等" },
  anthropic: { label: "Anthropic",      desc: "Anthropic Messages API（/v1/messages）" },
  ollama:    { label: "Ollama",          desc: "本地 Ollama 原生协议（/api/chat）" },
};

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
}

const SEED: ModelProvider[] = [
  {
    id: 1, name: "OpenAI", url: "https://api.openai.com/v1", masked: "sk-demo-••••••••4821", protocol: "openai" as const,
    enabled: true, status: "ok", models: ["GPT-4o", "GPT-4o mini", "o3-mini"],
  },
  {
    id: 2, name: "DeepSeek", url: "https://api.deepseek.com/v1", masked: "sk-ds-••••••••1a9b", protocol: "openai" as const,
    enabled: false, status: "untested", models: ["deepseek-chat", "deepseek-reasoner"],
  },
  {
    id: 3, name: "Ollama（本地）", url: "http://localhost:11434/v1", masked: "ollama-••••••••", protocol: "ollama" as const,
    enabled: false, status: "untested", models: ["qwen2.5:7b", "llama3.1:8b"],
  },
];

interface ModelProvidersState {
  providers: ModelProvider[];
  defaultModel: string;
  addProvider: (p: { name: string; url: string; key: string; protocol?: ProviderProtocol; models?: string[] }) => void;
  removeProvider: (id: number) => void;
  toggleEnabled: (id: number) => void;
  setDefaultModel: (m: string) => void;
  markStatus: (id: number, status: ModelProvider["status"]) => void;
}

/**
 * 模型服务商接入唯一数据源：模型管理页与对话页模型选择器共享。
 * 完整接入 = 名称 + 端点 URL + 密钥。
 */
export const useModelProviders = create<ModelProvidersState>()(
  persist(
    (set, get) => ({
      providers: SEED,
      defaultModel: "GPT-4o",
      addProvider: ({ name, url, key, protocol = "openai", models }) => {
        const provider: ModelProvider = {
          id: Date.now(),
          name: name.trim(),
          url: url.trim(),
          masked: key.slice(0, 4) + "••••••••" + key.slice(-4),
          enabled: true,
          status: "untested",
          protocol,
          models: models?.length ? models : ["默认模型"],
        };
        set((state) => ({ providers: [...state.providers, provider] }));
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
      },
      toggleEnabled: (id) =>
        set((state) => ({
          providers: state.providers.map((p) => (p.id === id ? { ...p, enabled: !p.enabled } : p)),
        })),
      setDefaultModel: (m) => set({ defaultModel: m }),
      markStatus: (id, status) =>
        set((state) => ({ providers: state.providers.map((p) => (p.id === id ? { ...p, status } : p)) })),
    }),
    { name: "model-providers" }
  )
);


