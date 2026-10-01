import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { ServiceInstance, LogEntry } from "@/types";
import { environmentApi } from "@/lib/services/environmentApi";
import { nowHms } from "@/lib/format";

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
  /**
   * 启动/停止(后端模式等真实结果:失败回滚乐观状态并返回 ok=false;
   * 2026-09-21 修「假成功」——此前后端以 status:"error" 返回失败时,
   * UI 仍无条件提示「已启动/已停止」)。
   */
  toggleService: (id: number) => Promise<{ ok: boolean; nextStatus: "running" | "stopped"; name: string; detail?: string }>;
  /** 重启(后端模式等真实结果;失败回滚并返回 ok=false)。 */
  restartService: (id: number) => Promise<{ ok: boolean; name: string; detail?: string }>;
  addLog: (entry: Omit<LogEntry, "time">) => void;
  /** 将 docker 原始日志行合并进 store(日志流订阅用) */
  ingestDockerLog: (service: string, line: string) => void;
}

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
  return nowHms();
}

/**
 * 环境微服务唯一数据源：环境控制台、顶部 Header 状态指标、
 * 首页环境看板共享，实现服务启停全站实时联动。
 * 服务列表/启停走 env-service 的真实 Docker 操作。
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
        await environmentApi.addManaged(input);
        await useServices.getState().syncFromBackend();},
      removeManaged: async (id) => {
        // PROC 源:先停托管进程再删记录,避免子进程变孤儿继续跑
        const target = get().services.find((s) => s.id === id);
        if (target?.kind === "PROC" && target.sourceId) {
          await environmentApi.stopService(target.name).catch(() => { /* 停失败也让删除继续,守护会按 STOPPED 意愿调和 */ });
        }
        set((state) => ({ services: state.services.filter((s) => s.id !== id) }));
        await environmentApi.deleteManaged(id).catch(() => { /* 乐观删除已生效 */ });
      },
      toggleService: async (id) => {
        const target = get().services.find((s) => s.id === id);
        if (!target) return { ok: false, nextStatus: "running" as const, name: "" };
        const nextStatus: "running" | "stopped" =
          target.status === "running" ? "stopped" : "running";

        // 乐观更新(界面即时反馈);后端失败时回滚(2026-09-21 修假成功)
        const applyOptimistic = (status: "running" | "stopped") => set((state) => ({
          services: state.services.map((s) =>
            s.id === id
              ? {
                  ...s,
                  status,
                  health: status === "running" ? "healthy" : "down",
                  uptime: status === "running" ? "刚刚" : "—",
                }
              : s
          ),
        }));
        // 回滚:恢复完整原始字段(状态可能是 running/stopped/error 三态,逐字段还原)
        const rollback = () => set((state) => ({
          services: state.services.map((s) =>
            s.id === id
              ? { ...s, status: target.status, health: target.health, uptime: target.uptime }
              : s
          ),
        }));
        applyOptimistic(nextStatus);

        const newLogs: LogEntry[] = [
          {
            time: nowHms(),
            level: nextStatus === "running" ? "info" : "warn",
            service: target.name,
            message: nextStatus === "running"
              ? "Container start requested via env-service."
              : "Container stop requested via env-service.",
          },
        ];
        set((state) => ({ logs: [...newLogs, ...state.logs].slice(0, 100) }));


        // 后端模式:等真实结果——后端以 code=0 + status:"error" 表达操作失败
        // (docker 不可用/容器不存在/PROC 启动失败),必须检查 status 字段
        try {
          const result = nextStatus === "running"
            ? await environmentApi.startService(target.name)
            : await environmentApi.stopService(target.name);
          if (result.status === "error") {
            rollback();
            return { ok: false, nextStatus, name: target.name, detail: result.detail };
          }
          // 成功后拉一次真实列表对齐(PROC 的 pid/uptime 等只有后端知道)
          void useServices.getState().syncFromBackend();
          return { ok: true, nextStatus, name: target.name, detail: result.detail };
        } catch (e) {
          rollback();
          return { ok: false, nextStatus, name: target.name,
            detail: e instanceof Error ? e.message : String(e) };
        }
      },
      restartService: async (id) => {
        const target = get().services.find((s) => s.id === id);
        if (!target) return { ok: false, name: "" };
        // 乐观更新(与旧行为一致:重启后运行中)
        const restartLog: LogEntry = {
          time: nowHms(),
          level: "info",
          service: target.name,
          message: "Container restart requested via env-service.",
        };
        set((state) => ({
          services: state.services.map((s) =>
            s.id === id ? { ...s, uptime: "刚刚", status: "running", health: "healthy" } : s
          ),
          logs: [restartLog, ...state.logs].slice(0, 100),
        }));
        try {
          const result = await environmentApi.restartService(target.name);
          if (result.status === "error") {
            // 回滚到重启前状态
            set((state) => ({
              services: state.services.map((s) =>
                s.id === id ? { ...s, status: target.status, health: target.health, uptime: target.uptime } : s
              ),
            }));
            return { ok: false, name: target.name, detail: result.detail };
          }
          void useServices.getState().syncFromBackend();
          return { ok: true, name: target.name, detail: result.detail };
        } catch (e) {
          set((state) => ({
            services: state.services.map((s) =>
              s.id === id ? { ...s, status: target.status, health: target.health, uptime: target.uptime } : s
            ),
          }));
          return { ok: false, name: target.name, detail: e instanceof Error ? e.message : String(e) };
        }
      },
      addLog: (entry) =>
        set((state) => ({
          logs: [{ ...entry, time: nowHms() }, ...state.logs].slice(0, 100),
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
