import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ServiceInstance, MOCK_SERVICES, LogEntry, MOCK_LOGS } from "@/lib/devData";

interface ServicesState {
  services: ServiceInstance[];
  logs: LogEntry[];
  toggleService: (id: number) => { nextStatus: "running" | "stopped"; name: string };
  restartService: (id: number) => string;
  addLog: (entry: Omit<LogEntry, "time">) => void;
}

const getLogTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

/**
 * 环境微服务唯一数据源：环境控制台、顶部 Header 状态指标、
 * 首页环境看板共享，实现服务启停全站实时联动。
 */
export const useServices = create<ServicesState>()(
  persist(
    (set) => ({
      services: MOCK_SERVICES,
      logs: MOCK_LOGS,
      toggleService: (id) => {
        let nextStatus: "running" | "stopped" = "running";
        let name = "";
        set((state) => {
          const target = state.services.find((s) => s.id === id);
          if (!target) return state;
          name = target.name;
          nextStatus = target.status === "running" ? "stopped" : "running";

          const newLogs: LogEntry[] = [];
          if (nextStatus === "running") {
            newLogs.push({
              time: getLogTime(),
              level: "info",
              service: name,
              message: "Container started and passed health checks.",
            });
            if (name === "redis") {
              newLogs.push({
                time: getLogTime(),
                level: "info",
                service: "api-gateway",
                message: "Redis connection established on port 6379, retry counter reset.",
              });
            }
          } else {
            newLogs.push({
              time: getLogTime(),
              level: "warn",
              service: name,
              message: "Container received SIGTERM, service stopped.",
            });
          }

          return {
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
          };
        });
        return { nextStatus, name };
      },
      restartService: (id) => {
        let name = "";
        set((state) => {
          const target = state.services.find((s) => s.id === id);
          if (!target) return state;
          name = target.name;

          const newLog: LogEntry = {
            time: getLogTime(),
            level: "info",
            service: name,
            message: "Container restarted successfully, health status: healthy.",
          };

          return {
            services: state.services.map((s) =>
              s.id === id ? { ...s, uptime: "刚刚", status: "running", health: "healthy" } : s
            ),
            logs: [newLog, ...state.logs].slice(0, 100),
          };
        });
        return name;
      },
      addLog: (entry) =>
        set((state) => ({
          logs: [{ ...entry, time: getLogTime() }, ...state.logs].slice(0, 100),
        })),
    }),
    { name: "environment-services" }
  )
);
