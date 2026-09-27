import { create } from "zustand";
import { API_BASE } from "@/lib/api/client";

/**
 * 后端可达性全局状态:所有页面共享。
 *
 * B7(2026-09-27,评审报告):此前唯一探针是 agent-service `/chat/health`——
 * agent 挂了整个工作台被替换成「服务不可用」页,即使文件/数据源服务仍可用,
 * 用户无法通过正常导航进入任何区域。现在拆成两个探针:
 *
 * - {@code online}(网关):探 `/api/auth/status`(gateway 自身端点,不经下游
 *   路由)——只有网关不可达才全屏替换(此时确实全站不可用);
 * - {@code agentOnline}(Agent):探 `/chat/health`——只影响助手/对话区域的
 *   局部提示(文件、任务、数据源等区域不受影响,由各页面自身错误处理)。
 *
 * 探测失败(网络层)按不可达处理;两个探针独立退避。
 */
interface BackendHealthState {
  /** null = 尚未完成首次探测;false = 网关不可达(全屏服务不可用页) */
  online: boolean | null;
  /** null = 尚未探测;agent-service 可用性(助手区域局部提示用) */
  agentOnline: boolean | null;
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

/** 网关探活:auth/status 由 gateway-service 自己服务(白名单免鉴权)。 */
async function probeGateway(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/auth/status`);
    return response.ok;
  } catch {
    return false;
  }
}

/** agent 探活:chat/health 经网关路由到 agent-service。 */
async function probeAgent(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/chat/health`);
    return response.ok;
  } catch {
    return false;
  }
}

export const useBackendHealth = create<BackendHealthState>()((set, get) => ({
  online: null,
  agentOnline: null,
  lastCheckedAt: null,
  check: async () => {
    const gatewayOk = await probeGateway();
    const wasOffline = get().online === false;
    const firstOnline = get().online === null && gatewayOk;
    set({ online: gatewayOk, lastCheckedAt: Date.now() });
    if (gatewayOk) {
      consecutiveFailures = 0;
      // 网关可达时探测 agent(独立,不阻塞网关状态)
      void probeAgent().then((agentOk) => set({ agentOnline: agentOk }));
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
        const { useAgentSettings } = await import("@/hooks/useChat");
        void useAgentSettings.getState().syncFromBackend();
      }
    } else {
      consecutiveFailures++;
      set({ agentOnline: false });
    }
    return gatewayOk;
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
  },
}));

/** 组件内便捷选择器(网关在线)。 */
export const useBackendOnline = () => useBackendHealth((s) => s.online);

/** agent 可用性选择器(B7:助手区域局部提示用;null=未探测)。 */
export const useAgentOnline = () => useBackendHealth((s) => s.agentOnline);
