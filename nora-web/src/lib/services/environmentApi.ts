import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { authHeaders, withAuthToken } from "@/lib/auth";
import type { ServiceInstance } from "@/types";

/** 后端纳管源列表行(/environment/services 返回,FILE/DOCKER/PROC 合一) */
export interface BackendServiceItem {
  id: string;
  name: string;
  kind: "DOCKER" | "FILE" | "PROC";
  containerName?: string;
  fileLogPath?: string;
  command?: string;
  workDir?: string;
  image?: string;
  status: "running" | "stopped" | "error";
  health: "healthy" | "degraded" | "down";
  port?: string;
  uptime?: string;
  cpu?: string;
  memory?: string;
  detail?: string;
}

function toService(item: BackendServiceItem, used?: Set<number>): ServiceInstance {
  return {
    id: hashId(item.id, used),
    name: item.name,
    image: item.image ?? (item.kind === "FILE" ? "进程日志源" : item.kind === "PROC" ? "平台托管进程" : "容器"),
    port: Number(item.port) || 0,
    status: item.status,
    health: item.health,
    uptime: item.uptime ?? item.detail ?? "—",
    cpu: item.cpu ?? "—",
    memory: item.memory ?? "—",
    kind: item.kind,
    fileLogPath: item.fileLogPath,
    command: item.command,
    workDir: item.workDir,
    sourceId: Number(item.id),
    detail: item.detail,
  };
}

/** docker 短 id(12位hex)/纳管源数字 id → 稳定前端数字 id;哈希碰撞时线性探测避让 */
function hashId(id: string, used?: Set<number>): number {
  const n = Number(id);
  if (Number.isFinite(n) && String(n) === id) return n;
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0;
  }
  let candidate = Math.abs(hash) || 1;
  // 同一批列表内碰撞则顺延,保证 id 唯一(卡片选中/日志订阅按 id 匹配)
  while (used && used.has(candidate)) {
    candidate += 1;
  }
  used?.add(candidate);
  return candidate;
}

/** 添加纳管源请求体 */
export interface AddManagedInput {
  kind: "FILE" | "DOCKER" | "PROC";
  name: string;
  fileLogPath?: string;
  containerName?: string;
  /** PROC 源:启动命令与工作目录 */
  command?: string;
  workDir?: string;
}

/**
 * 环境控制台后端接入层(USE_BACKEND 开关):
 * - listServices     → GET    /api/environment/services           → 纳管源(运行时状态已合并)
 * - addManaged       → POST   /api/environment/managed            → SourceView
 * - deleteManaged    → DELETE /api/environment/managed/{id}
 * - toggleManaged    → POST   /api/environment/managed/{id}/enabled
 * - startService     → POST   /api/environment/services/{name}/start   (DOCKER 源)
 * - stopService      → POST   /api/environment/services/{name}/stop
 * - restartService   → POST   /api/environment/services/{name}/restart
 * - analyzeSource    → POST   /api/environment/sources/{id}/analyze    (AI 只读分析)
 * 日志流:FILE/DOCKER 统一走 /api/environment/sources/{id}/logs/stream(SSE)
 */
export const environmentApi = {
  async listServices(): Promise<ServiceInstance[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendServiceItem[]>("/environment/services");
    const used = new Set<number>();
    return items.map((item) => toService(item, used));
  },

  async addManaged(input: AddManagedInput): Promise<{ id: number; name: string }> {
    return requestJson("/environment/managed", { method: "POST", body: JSON.stringify(input) });
  },

  async deleteManaged(id: number): Promise<boolean> {
    return requestJson(`/environment/managed/${id}`, { method: "DELETE" });
  },

  async toggleManaged(id: number, enabled: boolean): Promise<boolean> {
    return requestJson(`/environment/managed/${id}/enabled`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
  },

  async analyzeSource(id: number, tail = 100): Promise<{ status: string; analysis: string }> {
    return requestJson(`/environment/sources/${id}/analyze`, {
      method: "POST",
      body: JSON.stringify({ tail }),
    });
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

  /** PROC 守护事件增量(死亡/自愈/启动失败);after 为上次拉到的最新 ISO 时间戳 */
  async procEvents(after?: string): Promise<{ sourceId: number; name: string; type: string; detail: string; time: string }[]> {
    const qs = after ? `?after=${encodeURIComponent(after)}` : "";
    return requestJson(`/environment/proc-events${qs}`);
  },
};

/**
 * 订阅纳管源日志 SSE(按 sourceId,FILE/DOCKER 后端自动区分);返回取消函数。
 * 断线自动重连(指数退避,上限 15s,最多 5 次);onStatus 上报连接状态供 UI 展示。
 */
export function subscribeSourceLogs(
  sourceId: number,
  onLog: (line: string) => void,
  onError?: (error: Error) => void,
  onStatus?: (status: "connecting" | "open" | "reconnecting" | "closed") => void
): () => void {
  if (!USE_BACKEND) {
    onError?.(new Error("mock mode"));
    return () => undefined;
  }
  const controller = new AbortController();
  void (async () => {
    let attempt = 0;
    for (;;) {
      if (controller.signal.aborted) return;
      onStatus?.(attempt === 0 ? "connecting" : "reconnecting");
      try {
        const response = await fetch(
          withAuthToken(`/api/environment/sources/${sourceId}/logs/stream?tail=100`),
          { headers: authHeaders(), signal: controller.signal }
        );
        if (!response.ok || !response.body) {
          throw new Error(`HTTP ${response.status}`);
        }
        attempt = 0;
        onStatus?.("open");
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
        // 服务端正常关流:视为断线,走重连
      } catch (e) {
        if (controller.signal.aborted) return;
        onError?.(e as Error);
      }
      attempt += 1;
      if (attempt > 5) {
        onStatus?.("closed");
        return;
      }
      const delay = Math.min(1000 * 2 ** (attempt - 1), 15000);
      await new Promise((r) => setTimeout(r, delay));
    }
  })();
  return () => controller.abort();
}

export { toService };
