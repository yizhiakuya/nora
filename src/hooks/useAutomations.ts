import { create } from "zustand";
import { persist } from "zustand/middleware";
import { MOCK_AUTOMATIONS, AutomationRule } from "@/lib/devData";
import { useNotifications } from "./useNotifications";

interface AutomationsState {
  rules: AutomationRule[];
  addRule: (name: string, trigger: string, action: string) => AutomationRule;
  toggleRule: (id: number) => void;
  markRun: (id: number) => void;
}

/**
 * 自动任务唯一数据源：自动任务页、查询控制台「保存为自动任务」、
 * 环境「AI 诊断 → 创建修复任务」共享。
 */
export const useAutomations = create<AutomationsState>()(
  persist(
    (set) => ({
      rules: MOCK_AUTOMATIONS,
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
          if (rule) {
            useNotifications.getState().addNotification(
              "任务执行完成",
              `自动任务「${rule.name}」已成功触发，耗时 1.2s。`,
              "taskDone"
            );
          }
          return { rules: state.rules.map((r) => (r.id === id ? { ...r, lastRun: "刚刚" } : r)) };
        }),
    }),
    { name: "automations" }
  )
);
