import type { ExecutionRecord } from "@/types";
import { CheckCircle2, XCircle, Loader2, AlertTriangle, CircleSlash, HelpCircle, CalendarX } from "lucide-react";

/**
 * 执行终态的统一展示映射(B5,2026-09-27)。
 *
 * 问题(评审报告 B5):后端与执行历史已区分 partial/cancelled/unknown/
 * missed_schedule,但首页「最近成果」与「已保存成果」仍用
 * `status === "success" ? "成功" : "失败"` 二分——同一条记录在任务历史显示
 * 「结果未知」,在成果入口显示「失败」,用户无法判断该等待、核对还是重试。
 *
 * 三个消费方(ExecutionHistory / RecentResults / SavedResultsView)共用这一份,
 * 改状态语义时只改这里。
 */
export interface ExecutionStatusMeta {
  icon: React.ElementType;
  /** 图标/文字的 Tailwind 颜色类(明暗两套) */
  cls: string;
  label: string;
  /** 用户下一步动作提示(列表/详情 tooltip 用;无则 null) */
  actionHint: string | null;
}

export const EXECUTION_STATUS_META: Record<ExecutionRecord["status"], ExecutionStatusMeta> = {
  success:         { icon: CheckCircle2,  cls: "text-green-600 dark:text-green-400",   label: "成功",       actionHint: null },
  partial:         { icon: AlertTriangle, cls: "text-amber-600 dark:text-amber-400",   label: "部分完成",   actionHint: "有可用成果但存在未完成项,打开详情核对剩余部分" },
  failed:          { icon: XCircle,       cls: "text-red-600 dark:text-red-400",       label: "失败",       actionHint: "可到任务页重试,或打开详情查看失败原因" },
  cancelled:       { icon: CircleSlash,   cls: "text-gray-500 dark:text-gray-400",     label: "已取消",     actionHint: "已发生的操作保留在会话记录中" },
  unknown:         { icon: HelpCircle,    cls: "text-orange-600 dark:text-orange-400", label: "结果未知",   actionHint: "连接中断——该轮可能仍在后台运行,打开来源会话核对" },
  running:         { icon: Loader2,       cls: "text-blue-600 dark:text-blue-400",     label: "执行中",     actionHint: null },
  missed_schedule: { icon: CalendarX,     cls: "text-amber-600 dark:text-amber-400",   label: "错过计划点", actionHint: "超过宽限未自动补跑,可在任务页手动运行" },
};

/** 便捷取用(未知状态兜底 failed,绝不冒充成功)。 */
export function executionStatusMeta(status: ExecutionRecord["status"]): ExecutionStatusMeta {
  return EXECUTION_STATUS_META[status] ?? EXECUTION_STATUS_META.failed;
}
