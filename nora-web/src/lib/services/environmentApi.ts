import { requestJson, USE_BACKEND } from "@/lib/api/client";
import type { ServiceInstance } from "@/lib/devData";

/** 后端 ContainerView 行 */
export interface BackendContainer {
  id: string;
  name: string;
  image: string;
  status: "running" | "stopped" | "error";
  health: "healthy" | "degraded" | "down";
  port: string;
  uptime: string;
  cpu: string;
  memory: string;
}

function toService(c: BackendContainer): ServiceInstance {
  return {
    id: hashId(c.id),
    name: c.name,
    image: c.image,
    port: Number(c.port) || 0,
    status: c.status,
    health: c.health,
    uptime: c.uptime,
    cpu: c.cpu,
    memory: c.memory,
  };
}

/** docker 短 id(12位hex)→ 稳定数字 id,便于前端选择/切换 */
function hashId(id: string): number {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

/**
 * 环境控制台后端接入层(USE_BACKEND 开关):
 * - listServices  → GET  /api/environment/services          → ServiceInstance[]
 * - startService  → POST /api/environment/services/{name}/start
 * - stopService   → POST /api/environment/services/{name}/stop
 * - restartService→ POST /api/environment/services/{name}/restart
 * 日志流走原生 fetch + SSE(/api/environment/logs/stream?service=)
 */
export const environmentApi = {
  async listServices(): Promise<ServiceInstance[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendContainer[]>("/environment/services");
    return items.map(toService);
  },

  async startService(name: string): Promise<{ status: string; detail: string }> {
    return requestJson(`/environment/services/${encodeURIComponent(name)}/start`, { method: "POST" });
  },

  async stopService(name: string): Promise<{ status: string; detail: string }> {
    return requestJson(`/environment/services/${encodeURIComponent(name)}/stop`, { method: "POST" });
  },

  async restartService(name: string): Promise<{ status: string; detail: string }> {
    return requestJson(`/environment/services/${encodeURIComponent(name)}/restart`, { method: "POST" });
  },
};

/** 订阅容器日志 SSE;返回取消函数。 */
export function subscribeLogs(
  service: string,
  onLog: (line: string) => void,
  onError?: (error: Error) => void
): () => void {
  if (!USE_BACKEND) {
    onError?.(new Error("mock mode"));
    return () => undefined;
  }
  const controller = new AbortController();
  void (async () => {
    try {
      const response = await fetch(
        `/api/environment/logs/stream?service=${encodeURIComponent(service)}&tail=100`,
        { signal: controller.signal }
      );
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() ?? "";
        for (const block of blocks) {
          for (const line of block.split("\n")) {
            if (line.startsWith("data:")) {
              onLog(line.slice(5).replace(/^ /, ""));
            }
          }
        }
      }
    } catch (e) {
      if (!controller.signal.aborted) {
        onError?.(e as Error);
      }
    }
  })();
  return () => controller.abort();
}

export { toService };
