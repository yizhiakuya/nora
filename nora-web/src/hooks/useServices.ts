import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { ServiceInstance, LogEntry } from "@/types";
import { environmentApi } from "@/lib/services/environmentApi";
import { USE_BACKEND } from "@/lib/api/client";

interface ServicesState {
  services: ServiceInstance[];
  logs: LogEntry[];
  /** 当前选中的服务卡片(环境控制台卡片高亮 + 日志流联动展示该服务);null=自动取第一个 */
  activeServiceId: number | null;
  setActiveService: (id: number) => void;
  /** 清空某服务的日志(重新订阅 SSE 前/切换源时调用,避免 tail 回放产生重复行) */
  clearLogsFor: (service: string) => void;
  /** 后端模式:拉取纳管源列表(运行时状态已由后端合并) */
  syncFromBackend: () => Promise<void>;
  /** 添加纳管源(FILE 日志文件 / DOCKER 容器 / PROC 平台托管程序) */
  addManaged: (input: { kind: "FILE" | "DOCKER" | "PROC"; name: string; fileLogPath?: string; containerName?: string; command?: string; workDir?: string }) => Promise<void>;
  /** 删除纳管源(不动容器/文件本身) */
  removeManaged: (id: number) => Promise<void>;
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

/** 从原始日志行提取真实时间(HH:MM:SS);提不出则回落到当前时间 */
function extractLogTime(line: string): string {
  const iso = line.match(/T(\d{2}:\d{2}:\d{2})(?:\.\d+)?/);        // 2026-09-08T13:01:03.564+08:00
  if (iso) return iso[1];
  const plain = line.match(/\b(\d{2}:\d{2}:\d{2})(?:\.\d+)?\b/);   // 1:M 08 Sep 2026 01:16:41.510
  if (plain) return plain[1];
  return getLogTime();
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
      activeServiceId: null,
      setActiveService: (id) => set({ activeServiceId: id }),
      clearLogsFor: (service) =>
        set((state) => ({ logs: state.logs.filter((l) => l.service !== service) })),
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const services = await environmentApi.listServices();
          // 纳管清单是唯一事实来源:空列表也覆盖(空态引导用户添加),
          // 不再「沿用本地缓存」掩盖后端真实状态
          set({ services });
        } catch {
          /* 后端不可用时保留上次数据 */
        }
      },
      addManaged: async (input) => {
        if (!USE_BACKEND) {
          // mock 模式:本地直接加一张卡片
          const item: ServiceInstance = {
            id: Date.now(),
            name: input.name,
            image: input.kind === "FILE" ? "进程日志源" : input.kind === "PROC" ? "平台托管进程" : "容器",
            port: 0,
            status: "running",
            health: "healthy",
            uptime: "—",
            cpu: "—",
            memory: "—",
            kind: input.kind,
            fileLogPath: input.fileLogPath,
            detail: "本地 mock",
          };
          set((state) => ({ services: [item, ...state.services] }));
          return;
        }
        await environmentApi.addManaged(input);
        await useServices.getState().syncFromBackend();},
      removeManaged: async (id) => {
        // PROC 源:先停托管进程再删记录,避免子进程变孤儿继续跑
        const target = get().services.find((s) => s.id === id);
        if (USE_BACKEND && target?.kind === "PROC" && target.sourceId) {
          await environmentApi.stopService(target.name).catch(() => { /* 停失败也让删除继续,守护会按 STOPPED 意愿调和 */ });
        }
        set((state) => ({ services: state.services.filter((s) => s.id !== id) }));
        if (USE_BACKEND) {
          await environmentApi.deleteManaged(id).catch(() => { /* 乐观删除已生效 */ });
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
      /** 将 docker 原始日志行合并进 store(日志流订阅用);时间取自行内真实时间戳 */
      ingestDockerLog: (service: string, line: string) =>
        set((state) => ({
          logs: [
            { time: extractLogTime(line), level: levelOf(line), service, message: line.slice(0, 300) },
            ...state.logs,
          ].slice(0, 300),
        })),
    }) as ServicesState,
    {
      name: "environment-services",
      // 只持久化纳管清单与选中态;logs 高频变化,落 localStorage 是写放大
      partialize: (state) => ({ services: state.services, activeServiceId: state.activeServiceId }),
    }
  )
);
