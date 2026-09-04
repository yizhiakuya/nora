import { create } from "zustand";
import { persist } from "zustand/middleware";
import { MOCK_AUTOMATIONS, AutomationRule, MOCK_EXECUTIONS, ExecutionRecord } from "@/lib/devData";
import { useNotifications } from "./useNotifications";

interface AutomationsState {
  rules: AutomationRule[];
  executions: ExecutionRecord[];
  addRule: (name: string, trigger: string, action: string) => AutomationRule;
  toggleRule: (id: number) => void;
  markRun: (id: number) => void;
  retryExecution: (id: number) => void;
}

const getTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

/**
 * 自动任务唯一数据源：自动任务页、查询控制台「保存为自动任务」、
 * 环境「AI 诊断 → 创建修复任务」共享。
 */
export const useAutomations = create<AutomationsState>()(
  persist(
    (set) => ({
      rules: MOCK_AUTOMATIONS,
      executions: MOCK_EXECUTIONS,
      addRule: (name, trigger, action) => {
        const rule: AutomationRule = {
          id: Date.now(),
          name,
          trigger,
          action,
          enabled: true,
          lastRun: "—",
          status: "active",
        };
        set((state) => ({ rules: [rule, ...state.rules] }));
        useNotifications.getState().addNotification("新任务已创建", `自动任务「${name}」已添加，触发条件：${trigger}。`);
        return rule;
      },
      toggleRule: (id) =>
        set((state) => ({
          rules: state.rules.map((r) =>
            r.id === id
              ? { ...r, enabled: !r.enabled, status: r.enabled ? "paused" : "active" }
              : r
          ),
        })),
      markRun: (id) =>
        set((state) => {
          const rule = state.rules.find((r) => r.id === id);
          if (!rule) return state;

          useNotifications.getState().addNotification(
            "任务执行完成",
            `自动任务「${rule.name}」已成功触发，耗时 1.2s。`,
            "taskDone"
          );

          const newExec: ExecutionRecord = {
            id: Date.now(),
            ruleName: rule.name,
            time: getTime(),
            duration: "1.2s",
            status: "success",
            detail: `${rule.action}（触发完成）`,
          };

          return {
            rules: state.rules.map((r) => (r.id === id ? { ...r, lastRun: "刚刚" } : r)),
            executions: [newExec, ...state.executions].slice(0, 50),
          };
        }),
      retryExecution: (id) =>
        set((state) => ({
          executions: state.executions.map((e) =>
            e.id === id
              ? { ...e, status: "success", time: getTime(), duration: "1.4s", detail: `${e.detail} → 重试成功` }
              : e
          ),
        })),
    }),
    { name: "automations" }
  )
);
