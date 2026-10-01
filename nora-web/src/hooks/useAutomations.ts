import { create } from "zustand";
import { persist } from "zustand/middleware";
import { toast } from "sonner";
import type { AutomationRule, ExecutionRecord } from "@/types";
import { useNotifications } from "./useNotifications";
import { automationsApi } from "@/lib/services/automationsApi";
import { humanizeError } from "@/lib/errorMessages";
import { nowHm } from "@/lib/format";

/** 错误 → 人话(优先按结构化分类;网关 JSON/网络异常走启发式兜底)。 */
function friendly(e: unknown): string {
  return humanizeError(e).message;
}

/** 执行终态 → 通知文案(F2:partial/cancelled/unknown 不再冒充成功或失败)。 */
function execStatusText(status: ExecutionRecord["status"]): string {
  switch (status) {
    case "success": return "执行成功";
    case "partial": return "部分完成(存在未完成项,详情见执行历史)";
    case "cancelled": return "已取消(未产生完整结果)";
    case "unknown": return "结果未知(连接中断,该轮可能仍在后台运行)";
    default: return "执行失败";
  }
}

interface AutomationsState {
  rules: AutomationRule[];
  executions: ExecutionRecord[];
  /** 后端模式:拉取服务端规则与执行历史 */
  syncFromBackend: () => Promise<void>;
  /** 创建规则;后端模式失败返回 null(已提示,绝不产生"未创建却成功"的幽灵条目) */
  /** 创建规则;后端模式失败返回 null(已提示,绝不产生"未创建却成功"的幽灵条目)。
   *  schedule(M4-01):daily/weekly 必填日程(daily/weekly 规则后端强制校验)。
   *  connectionId(B3,2026-09-27):SQL 动作的目标数据源——随规则落库,执行时
   *  优先使用,不再默认「列表第一项」(多库时执行目标丢失)。 */
  addRule: (name: string, trigger: string, action: string,
            schedule?: import("@/lib/services/automationsApi").ScheduleSpec,
            connectionId?: number) => Promise<AutomationRule | null>;
  /** 启用/暂停规则;返回是否真实切换成功(后端模式等服务器结果,失败回滚) */
  toggleRule: (id: number) => Promise<boolean>;
  /** 立即运行;返回是否真实执行成功(后端模式等待服务器结果) */
  markRun: (id: number) => Promise<boolean>;
  retryExecution: (id: number) => void;
}


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
 * CRUD 与执行走 automation-service /api/automations。
 */
export const useAutomations = create<AutomationsState>()(
  persist(
    (set, get) => ({
      rules: [],
      executions: [],
      syncFromBackend: async () => {
        try {
          const [rules, executions] = await Promise.all([
            automationsApi.listRules(),
            automationsApi.listExecutions(),
          ]);
          // 服务端是权威数据源:空数组同样覆盖本地缓存(此前 length>0 才覆盖,
          // 服务端清空后界面会继续展示旧数据)
          set({ rules, executions });
        } catch {
          /* 后端不可用时沿用本地缓存 */
        }
      },
      addRule: async (name, trigger, action, schedule, connectionId) => {
        const isSqlAction = looksLikeSql(action);
        // 后端模式:等服务器真实结果再落状态(2026-09-19 修假成功)。
        // 此前先插乐观条目、失败也保留,且弹窗立即提示"创建成功"——
        // 后端失败时用户看到一条从未存在的规则,点运行还会走本地假执行。
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
        try {
          const saved = await automationsApi.createRule(
            isSqlAction
              ? { name, triggerType: triggerTypeFromLabel(trigger), actionType: "sql", sql: action, schedule: schedule ? JSON.stringify(schedule) : undefined, connectionId }
              : { name, triggerType: triggerTypeFromLabel(trigger), actionType: "agent", prompt: action, schedule: schedule ? JSON.stringify(schedule) : undefined },
          );
          set((state) => ({
            rules: state.rules.map((r) => (r.id === optimistic.id ? saved : r)),
          }));
          useNotifications.getState().addNotification("新任务已创建", `自动任务「${name}」已添加，触发条件：${trigger}。`);
          return saved;
        } catch (e) {
          // 失败:回滚乐观条目 + 明确报错,不产生幽灵规则
          set((state) => ({ rules: state.rules.filter((r) => r.id !== optimistic.id) }));
          toast.error(`创建自动任务失败：${friendly(e)}`);
          return null;
        }
      },
      toggleRule: async (id) => {
        const target = get().rules.find((r) => r.id === id);
        if (!target) return false;
        const nextEnabled = !target.enabled;
        const rollback = () => set((state) => ({
          rules: state.rules.map((r) => (r.id === id ? { ...r, enabled: target.enabled } : r)),
        }));
        // 乐观更新(界面即时反馈)
        set((state) => ({
          rules: state.rules.map((r) =>
            r.id === id
              ? { ...r, enabled: nextEnabled, status: nextEnabled ? "active" : "paused" }
              : r
          ),
        }));
        if (id >= 1e12) {
          // 乐观条目(尚未保存成功):不能切;回滚并提示(与 markRun 同语义)
          rollback();
          toast.error(`「${target.name}」尚未保存成功，无法启用/暂停；请稍后重试或重新创建`);
          return false;
        }
        // R06(2026-09-20 修复):等服务器真实结果——此前 fire-and-forget +
        // 空 catch,后端失败界面仍显示成功。失败回滚并提示。
        try {
          await automationsApi.toggleRule(id);
          return true;
        } catch (e) {
          rollback();
          toast.error(`「${target.name}」${nextEnabled ? "启用" : "暂停"}失败：${friendly(e)}`);
          return false;
        }
      },
      markRun: async (id) => {
        const rule = get().rules.find((r) => r.id === id);
        if (!rule) return false;
        if (id >= 1e12) {
          // 乐观条目(尚未拿到服务端 id):此前会落入本地假执行分支,
          // 生成固定"1.2s / success"记录——后端失败也能"执行成功"(2026-09-19 修)。
          // 现在明确拒绝,等创建结果落定后再运行。
          toast.error(`「${rule.name}」尚未保存成功，无法运行；请稍后重试或重新创建`);
          return false;
        }
        try {
          const exec = await automationsApi.runRule(id);
          useNotifications.getState().addNotification(
            "任务执行完成",
            `自动任务「${rule.name}」${execStatusText(exec.status)}，耗时 ${exec.duration}。`,
            exec.status === "success" ? "taskDone" : "taskFail"
          );
          set((state) => ({
            rules: state.rules.map((r) => (r.id === id ? { ...r, lastRun: "刚刚" } : r)),
            executions: [exec, ...state.executions].slice(0, 50),
          }));
          return exec.status === "success";
        } catch (e) {
          useNotifications.getState().addNotification(
            "任务执行失败",
            `自动任务「${rule.name}」执行失败：${friendly(e)}`,
            "taskDone"
          );
          return false;
        }
      },
      retryExecution: (id) => {
        // 真实重试(2026-09-19 去假功能):此前只本地改状态 + 假动画,
        // 从未真正重跑。现在按执行记录定位规则,真调后端 run 端点,
        // 完成后拉一次执行历史刷新(与手动「立即运行」同一条链路)。
        const exec = get().executions.find((e) => e.id === id);
        if (!exec || !exec.ruleId) {
          // 旧数据无 ruleId:本地提示(不假装成功)
          useNotifications.getState().addNotification(
            "无法重试", `「${exec?.ruleName ?? "?"}」缺少规则信息,请到自动任务页手动运行。`, "taskDone");
          return;
        }
        void automationsApi.runRule(exec.ruleId)
          .then((fresh) => {
            useNotifications.getState().addNotification(
              "任务执行完成",
              `自动任务「${exec.ruleName}」重试${execStatusText(fresh.status)},耗时 ${fresh.duration}。`,
              fresh.status === "success" ? "taskDone" : "taskFail");
            set((state) => ({
              executions: [fresh, ...state.executions].slice(0, 50),
            }));
          })
          .catch((e: unknown) => {
            useNotifications.getState().addNotification(
              "任务执行失败", `自动任务「${exec.ruleName}」重试失败:${friendly(e)}`, "taskFail");
          });
      },
    }),
    { name: "automations" }
  )
);
