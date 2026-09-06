import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { ServiceInstance, LogEntry } from "@/types";
import { environmentApi } from "@/lib/services/environmentApi";
import { USE_BACKEND } from "@/lib/api/client";

interface ServicesState {
  services: ServiceInstance[];
  logs: LogEntry[];
  /** 后端模式:拉取真实 Docker 容器列表 */
  syncFromBackend: () => Promise<void>;
  toggleService: (id: number) => { nextStatus: "running" | "stopped"; name: string };
  restartService: (id: number) => string;
  addLog: (entry: Omit<LogEntry, "time">) => void;
  /** 将 docker 原始日志行合并进 store(日志流订阅用) */
  ingestDockerLog: (service: string, line: string) => void;
}

const getLogTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

/** docker 日志行 → level 粗分级(供前端着色) */
function levelOf(line: string): LogEntry["level"] {
  const lower = line.toLowerCase();
  if (/\berror\b|\bexception\b|fatal/.test(lower)) return "error";
  if (/\bwarn\b/.test(lower)) return "warn";
  return "info";
}

/**
 * 环境微服务唯一数据源：环境控制台、顶部 Header 状态指标、
 * 首页环境看板共享，实现服务启停全站实时联动。
 * USE_BACKEND 时服务列表/启停走 env-service 的真实 Docker 操作。
 */
export const useServices = create<ServicesState>()(
  persist(
    (set, get) => ({
      services: [],
      logs: [],
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const services = await environmentApi.listServices();
          if (services.length > 0) {
            set({ services });
          }
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      toggleService: (id) => {
        const target = get().services.find((s) => s.id === id);
        if (!target) return { nextStatus: "running" as const, name: "" };
        const nextStatus: "running" | "stopped" =
          target.status === "running" ? "stopped" : "running";

        if (USE_BACKEND) {
          const action = nextStatus === "running"
            ? environmentApi.startService(target.name)
            : environmentApi.stopService(target.name);
          action.then((result) => {
            if (result.status === "error") return;
            void useServices.getState().syncFromBackend();
          }).catch(() => { /* 乐观更新已生效 */ });
        }

        const newLogs: LogEntry[] = [
          {
            time: getLogTime(),
            level: nextStatus === "running" ? "info" : "warn",
            service: target.name,
            message: nextStatus === "running"
              ? "Container start requested via env-service."
              : "Container stop requested via env-service.",
          },
        ];
        set((state) => ({
          services: state.services.map((s) =>
            s.id === id
              ? {
                  ...s,
                  status: nextStatus,
                  health: nextStatus === "running" ? "healthy" : "down",
                  uptime: nextStatus === "running" ? "刚刚" : "—",
                }
              : s
          ),
          logs: [...newLogs, ...state.logs].slice(0, 100),
        }));
        return { nextStatus, name: target.name };
      },
      restartService: (id) => {
        const target = get().services.find((s) => s.id === id);
        if (!target) return "";
        if (USE_BACKEND) {
          environmentApi.restartService(target.name)
            .then(() => void useServices.getState().syncFromBackend())
            .catch(() => { /* 乐观更新已生效 */ });
        }
        const newLog: LogEntry = {
          time: getLogTime(),
          level: "info",
          service: target.name,
          message: "Container restart requested via env-service.",
        };
        set((state) => ({
          services: state.services.map((s) =>
            s.id === id ? { ...s, uptime: "刚刚", status: "running", health: "healthy" } : s
          ),
          logs: [newLog, ...state.logs].slice(0, 100),
        }));
        return target.name;
      },
      addLog: (entry) =>
        set((state) => ({
          logs: [{ ...entry, time: getLogTime() }, ...state.logs].slice(0, 100),
        })),
      /** 将 docker 原始日志行合并进 store(日志流订阅用) */
      ingestDockerLog: (service: string, line: string) =>
        set((state) => ({
          logs: [
            { time: getLogTime(), level: levelOf(line), service, message: line.slice(0, 300) },
            ...state.logs,
          ].slice(0, 100),
        })),
    }) as ServicesState,
    { name: "environment-services" }
  )
);
