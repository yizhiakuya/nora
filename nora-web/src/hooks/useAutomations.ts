import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { AutomationRule, ExecutionRecord } from "@/types";
import { useNotifications } from "./useNotifications";
import { automationsApi } from "@/lib/services/automationsApi";
import { USE_BACKEND } from "@/lib/api/client";

interface AutomationsState {
  rules: AutomationRule[];
  executions: ExecutionRecord[];
  /** 后端模式:拉取服务端规则与执行历史 */
  syncFromBackend: () => Promise<void>;
  addRule: (name: string, trigger: string, action: string) => AutomationRule;
  toggleRule: (id: number) => void;
  markRun: (id: number) => Promise<void>;
  retryExecution: (id: number) => void;
}

const getTime = () =>
  new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

/** 前端 trigger 标签 → 后端 triggerType */
function triggerTypeFromLabel(label: string): string {
  if (label.includes("每日")) return "daily";
  if (label.includes("每周")) return "weekly";
  if (label.includes("文件")) return "file";
  if (label.includes("异常") || label.includes("ERROR")) return "error";
  return "manual";
}

/** action 文本是否像 SQL(用于后端模式下的动作识别) */
function looksLikeSql(action: string): boolean {
  return /^\s*(SELECT|SHOW|EXPLAIN)\b/i.test(action.trim());
}

/**
 * 自动任务唯一数据源：自动任务页、查询控制台「保存为自动任务」、
 * 环境「AI 诊断 → 创建修复任务」共享。
 * USE_BACKEND 时 CRUD 与执行走 automation-service /api/automations。
 */
export const useAutomations = create<AutomationsState>()(
  persist(
    (set, get) => ({
      rules: [],
      executions: [],
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const [rules, executions] = await Promise.all([
            automationsApi.listRules(),
            automationsApi.listExecutions(),
          ]);
          if (rules.length > 0) set({ rules });
          if (executions.length > 0) set({ executions });
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addRule: (name, trigger, action) => {
        const isSqlAction = looksLikeSql(action);
        if (USE_BACKEND) {
          // 后端模式:SQL 动作走 sql 分支;其余(NL 指令)走 agent 分支,
          // 由 agent-service 执行(RAG + 工具循环),不再落回本地 mock
          const optimistic: AutomationRule = {
            id: Date.now(),
            name,
            trigger,
            action,
            enabled: true,
            lastRun: "—",
            status: "active",
          };
          set((state) => ({ rules: [optimistic, ...state.rules] }));
          automationsApi
            .createRule(
              isSqlAction
                ? { name, triggerType: triggerTypeFromLabel(trigger), actionType: "sql", sql: action }
                : { name, triggerType: triggerTypeFromLabel(trigger), actionType: "agent", prompt: action },
            )
            .then((saved) => {
              set((state) => ({
                rules: state.rules.map((r) => (r.id === optimistic.id ? saved : r)),
              }));
            })
            .catch(() => { /* 保留乐观条目 */ });
          useNotifications.getState().addNotification("新任务已创建", `自动任务「${name}」已添加，触发条件：${trigger}。`);
          return optimistic;
        }
        // Mock 模式:本地行为
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
      toggleRule: (id) => {
        const target = get().rules.find((r) => r.id === id);
        set((state) => ({
          rules: state.rules.map((r) =>
            r.id === id
              ? { ...r, enabled: !r.enabled, status: r.enabled ? "paused" : "active" }
              : r
          ),
        }));
        if (USE_BACKEND && target && id < 1e12) {
          automationsApi.toggleRule(id).catch(() => { /* 乐观更新已生效 */ });
        }
      },
      markRun: async (id) => {
        const rule = get().rules.find((r) => r.id === id);
        if (!rule) return;
        if (USE_BACKEND && id < 1e12) {
          try {
            const exec = await automationsApi.runRule(id);
            useNotifications.getState().addNotification(
              "任务执行完成",
              `自动任务「${rule.name}」执行${exec.status === "success" ? "成功" : "失败"}，耗时 ${exec.duration}。`,
              "taskDone"
            );
            set((state) => ({
              rules: state.rules.map((r) => (r.id === id ? { ...r, lastRun: "刚刚" } : r)),
              executions: [exec, ...state.executions].slice(0, 50),
            }));
            return;
          } catch (e) {
            useNotifications.getState().addNotification(
              "任务执行失败",
              `自动任务「${rule.name}」执行失败：${(e as Error).message}`,
              "taskDone"
            );
            return;
          }
        }
        // Mock 模式
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
        set((state) => ({
          rules: state.rules.map((r) => (r.id === id ? { ...r, lastRun: "刚刚" } : r)),
          executions: [newExec, ...state.executions].slice(0, 50),
        }));
      },
      retryExecution: (id) => {
        // 真实重试(2026-09-19 去假功能):此前只本地改状态 + 假动画,
        // 从未真正重跑。现在按执行记录定位规则,真调后端 run 端点,
        // 完成后拉一次执行历史刷新(与手动「立即运行」同一条链路)。
        const exec = get().executions.find((e) => e.id === id);
        if (!exec || !USE_BACKEND || !exec.ruleId) {
          // Mock 模式/旧数据无 ruleId:本地提示(不假装成功)
          useNotifications.getState().addNotification(
            "无法重试", `「${exec?.ruleName ?? "?"}」缺少规则信息,请到自动任务页手动运行。`, "taskDone");
          return;
        }
        void automationsApi.runRule(exec.ruleId)
          .then((fresh) => {
            useNotifications.getState().addNotification(
              "任务执行完成",
              `自动任务「${exec.ruleName}」重试${fresh.status === "success" ? "成功" : "失败"},耗时 ${fresh.duration}。`,
              "taskDone");
            set((state) => ({
              executions: [fresh, ...state.executions].slice(0, 50),
            }));
          })
          .catch((e: Error) => {
            useNotifications.getState().addNotification(
              "任务执行失败", `自动任务「${exec.ruleName}」重试失败:${e.message}`, "taskFail");
          });
      },
    }),
    { name: "automations" }
  )
);
