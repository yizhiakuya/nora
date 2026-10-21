import { create } from "zustand";
import { API_BASE } from "@/lib/api/client";

/** Single backend availability shared by all pages. */
interface BackendHealthState {
  /** null = 尚未完成首次探测 */
  online: boolean | null;
  /** 最近一次探测时间(ms) */
  lastCheckedAt: number | null;
  check: () => Promise<boolean>;
  /** 启动轮询(应用入口调用一次) */
  startPolling: () => void;
}

const POLL_MS = 30_000;
/** 离线时的自动重试间隔(指数退避上限 30s);在线时回到常规轮询 */
const RETRY_STEPS_MS = [5_000, 10_000, 20_000, 30_000];
let pollingStarted = false;
let timer: ReturnType<typeof setTimeout> | null = null;
let consecutiveFailures = 0;

async function probeBackend(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/auth/status`, { signal: AbortSignal.timeout(5_000) });
    return response.ok;
  } catch {
    return false;
  }
}

export const useBackendHealth = create<BackendHealthState>()((set, get) => ({
  online: null,
  lastCheckedAt: null,
  check: async () => {
    const backendOk = await probeBackend();
    const wasOffline = get().online === false;
    const firstOnline = get().online === null && backendOk;
    set({ online: backendOk, lastCheckedAt: Date.now() });
    if (backendOk) {
      consecutiveFailures = 0;
      const { useAgentSettings } = await import("@/hooks/useAgentSettings");
      void useAgentSettings.getState().syncFromBackend();
      // 首次探测成功(online 从 null 转 true)或离线恢复:重新拉数据
      // (动态 import 避免环)。首次也要拉——agent 设置等 store 的本地
      // 缓存可能是空的,不拉的话设置永远到不了 UI
      if (wasOffline || firstOnline) {
        const { useModelProviders } = await import("@/hooks/useModelProviders");
        void useModelProviders.getState().syncFromBackend();
        const { useFiles } = await import("@/hooks/useFiles");
        void useFiles.getState().syncFromBackend();
        const { useConnections } = await import("@/hooks/useConnections");
        void useConnections.getState().syncFromBackend();
        const { useChatSessions } = await import("@/hooks/useChatSessions");
        void useChatSessions.getState().syncFromBackend();
        const { useServices } = await import("@/hooks/useServices");
        void useServices.getState().syncFromBackend();
        const { useAutomations } = await import("@/hooks/useAutomations");
        void useAutomations.getState().syncFromBackend();
        const { useKnowledgeDocs } = await import("@/hooks/useKnowledgeDocs");
        void useKnowledgeDocs.getState().syncFromBackend();
      }
    } else {
      consecutiveFailures++;
    }
    return backendOk;
  },
  startPolling: () => {
    if (pollingStarted) return;
    pollingStarted = true;
    const loop = async () => {
      await get().check();
      if (timer) clearTimeout(timer);
      // 在线:常规 30s 轮询;离线:指数退避(5s→10s→20s→30s 封顶)加速恢复感知
      const nextMs = get().online === false
        ? RETRY_STEPS_MS[Math.min(consecutiveFailures - 1, RETRY_STEPS_MS.length - 1)]
        : POLL_MS;
      timer = setTimeout(loop, Math.max(1_000, nextMs));
    };
    void loop();
    // 页面重新可见时立即探测(笔记本唤醒/切网)
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") void get().check();
    });
    window.addEventListener("storage", event => {
      if (event.key === "chat-agent-settings") {
        void import("@/hooks/useAgentSettings").then(({ useAgentSettings }) => useAgentSettings.getState().syncFromBackend());
      }
    });
  },
}));

/** Chat shares the same backend availability. */
export const useAgentOnline = () => useBackendHealth((s) => s.online);
