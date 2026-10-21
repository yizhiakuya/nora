import { create } from "zustand";
import { toast } from "sonner";
import type { AutomationRule, ExecutionRecord } from "@/types";
import { useNotifications, sendBrowserNotification } from "./useNotifications";
import { automationsApi, type AutomationAction, type AutomationTrigger, type ScheduleSpec } from "@/lib/services/automationsApi";

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function execStatusText(status: ExecutionRecord["status"]): string {
  switch (status) {
    case "success": return "执行成功";
    case "partial": return "部分完成";
    case "cancelled": return "已取消";
    case "unknown": return "结果未知，请核实执行历史";
    case "queued": return "正在排队";
    case "running": return "仍在执行";
    default: return "执行失败";
  }
}

interface AutomationsState {
  rules: AutomationRule[];
  executions: ExecutionRecord[];
  runningRuleIds: number[];
  togglingRuleIds: number[];
  syncError: string | null;
  syncFromBackend: () => Promise<void>;
  addRule: (name: string, trigger: AutomationTrigger, action: AutomationAction,
    schedule?: ScheduleSpec) => Promise<AutomationRule | null>;
  toggleRule: (id: number) => Promise<boolean>;
  markRun: (id: number) => Promise<boolean>;
  retryExecution: (id: number) => Promise<boolean>;
}

// 新的同步或成功写入会使旧快照失效，避免迟到的列表覆盖刚保存的结果。
let revision = 0;

export const useAutomations = create<AutomationsState>()(
  (set, get) => ({
    rules: [],
    executions: [],
    runningRuleIds: [],
    togglingRuleIds: [],
    syncError: null,
    syncFromBackend: async () => {
      const request = ++revision;
      try {
        const [rules, executions] = await Promise.all([
          automationsApi.listRules(),
          automationsApi.listExecutions(),
        ]);
        if (request === revision) set({ rules, executions, syncError: null });
      } catch (error) {
        if (request === revision) set({ syncError: errorMessage(error) });
      }
    },
    addRule: async (name, trigger, action, schedule) => {
      try {
        const saved = await automationsApi.createRule({
          name, triggerType: trigger, ...action,
          schedule: schedule ? JSON.stringify(schedule) : undefined,
        });
        revision++;
        set((state) => ({ rules: [saved, ...state.rules.filter((r) => r.id !== saved.id)] }));
        useNotifications.getState().addNotification("新任务已创建", `自动任务「${name}」已添加，触发条件：${saved.trigger}。`);
        return saved;
      } catch (error) {
        toast.error(`创建自动任务失败：${errorMessage(error)}`);
        return null;
      }
    },
    toggleRule: async (id) => {
      if (get().togglingRuleIds.includes(id)) return false;
      set((state) => ({ togglingRuleIds: [...state.togglingRuleIds, id] }));
      try {
        const saved = await automationsApi.toggleRule(id);
        revision++;
        set((state) => ({ rules: state.rules.map((r) => r.id === id ? saved : r) }));
        return true;
      } catch (error) {
        toast.error(`切换任务状态失败：${errorMessage(error)}`);
        return false;
      } finally {
        set((state) => ({ togglingRuleIds: state.togglingRuleIds.filter((value) => value !== id) }));
      }
    },
    markRun: async (id) => {
      if (get().runningRuleIds.includes(id)) return false;
      const name = get().rules.find((r) => r.id === id)?.name
        ?? get().executions.find((e) => e.ruleId === id)?.ruleName;
      if (!name) return false;
      const toastId = `run-${id}`;
      toast.loading(`「${name}」执行中…`, { id: toastId });
      set((state) => ({ runningRuleIds: [...state.runningRuleIds, id] }));
      try {
        const exec = await automationsApi.runRule(id);
        revision++;
        set((state) => ({ executions: [exec, ...state.executions.filter((e) => e.id !== exec.id)].slice(0, 50) }));
        const message = `「${name}」${execStatusText(exec.status)}`;
        if (exec.status === "success") toast.success(message, { id: toastId });
        else if (exec.status === "failed") toast.error(message, { id: toastId, description: exec.detail });
        else toast.info(message, { id: toastId });
        if (exec.status !== "running" && exec.status !== "queued") {
          sendBrowserNotification("任务执行结果", message, exec.status === "success" ? "taskDone" : "taskFail");
        }
        // 执行通知已由后端发布，客户端只同步，避免每次运行产生两条通知。
        void useNotifications.getState().syncFromBackend();
        return exec.status === "success";
      } catch (error) {
        toast.error(`「${name}」执行请求失败：${errorMessage(error)}`, { id: toastId });
        return false;
      } finally {
        set((state) => ({ runningRuleIds: state.runningRuleIds.filter((value) => value !== id) }));
        void get().syncFromBackend();
      }
    },
    retryExecution: async (id) => {
      const exec = get().executions.find((e) => e.id === id);
      if (exec?.ruleId == null) {
        toast.error("无法重试：执行记录缺少规则信息");
        return false;
      }
      return get().markRun(exec.ruleId);
    },
  })
);
