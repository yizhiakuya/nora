import { requestJson, USE_BACKEND } from "@/lib/api/client";
import type { ModelProvider, ProviderProtocol } from "@/hooks/useModelProviders";

/** 后端 model_provider 行(ProviderView,camelCase) */
export interface BackendProvider {
  id: number;
  name: string;
  protocol: ProviderProtocol;
  endpoint: string;
  masked: string;
  enabled: boolean;
  models: string[];
  status: "untested" | "ok" | "fail";
}

function toProvider(p: BackendProvider): ModelProvider {
  return {
    id: p.id,
    name: p.name,
    url: p.endpoint ?? "",
    masked: p.masked ?? "—",
    enabled: p.enabled,
    models: p.models ?? [],
    status: p.status ?? "untested",
    protocol: p.protocol ?? "openai",
  };
}

/**
 * 模型服务商后端接入层(USE_BACKEND 开关):
 * - listProviders  → GET    /api/models/providers        → ModelProvider[]
 * - createProvider → POST   /api/models/providers        → ModelProvider
 * - updateProvider → PUT    /api/models/providers/{id}   → ModelProvider
 * - deleteProvider → DELETE /api/models/providers/{id}
 * - testProvider   → POST   /api/models/providers/{id}/test → {status}
 */
export const modelsApi = {
  async listProviders(): Promise<ModelProvider[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendProvider[]>("/models/providers");
    return items.map(toProvider);
  },

  async createProvider(input: {
    name: string;
    protocol: ProviderProtocol;
    endpoint: string;
    apiKey: string;
    models?: string[];
  }): Promise<ModelProvider> {
    const item = await requestJson<BackendProvider>("/models/providers", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return toProvider(item);
  },

  async updateProvider(
    id: number,
    patch: { name?: string; enabled?: boolean; models?: string[] }
  ): Promise<ModelProvider> {
    const item = await requestJson<BackendProvider>(`/models/providers/${id}`, {
      method: "PUT",
      body: JSON.stringify(patch),
    });
    return toProvider(item);
  },

  async deleteProvider(id: number): Promise<void> {
    await requestJson<void>(`/models/providers/${id}`, { method: "DELETE" });
  },

  async testProvider(
    id: number
  ): Promise<{ status: "ok" | "fail"; error?: string | null; modelCount?: number }> {
    return requestJson<{ status: "ok" | "fail"; error?: string | null; modelCount?: number }>(
      `/models/providers/${id}/test`,
      { method: "POST" }
    );
  },

  /** 测试连通并返回刷新后的 provider(模型列表已被后端自动更新为上游模型) */
  async testAndRefresh(
    id: number,
    applyProvider: (p: ModelProvider) => void
  ): Promise<{ status: "ok" | "fail"; modelCount?: number }> {
    const result = await this.testProvider(id);
    if (result.status === "ok") {
      const items = await requestJson<BackendProvider[]>("/models/providers");
      const updated = items.find((p) => p.id === id);
      if (updated) applyProvider(toProvider(updated));
    }
    return result;
  },
};
