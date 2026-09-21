import { requestJson, USE_BACKEND, defaultTimeoutSignal } from "@/lib/api/client";
import type { AutomationRule, ExecutionRecord } from "@/types";

/** 后端 automation_rule 行(M4-01 扩展日程字段) */
export interface BackendRule {
  id: number;
  name: string;
  triggerType: string;
  triggerLabel: string;
  actionJson: string;
  enabled: boolean;
  status: string;
  lastRunAt: string | null;
  /** 日程 JSON(M4-01;null = 手动/需配置) */
  schedule: string | null;
  /** 下一次计划执行(派生;手动/需配置为 null) */
  nextRunAt: string | null;
  /** ok / needs_config */
  configurationStatus: string;
}

/** 日程契约(M4-01;与后端 ScheduleCalculator 对齐)。 */
export interface ScheduleSpec {
  frequency: "daily" | "weekly";
  /** HH:mm */
  localTime: string;
  /** weekly:1-7(1=周一) */
  dayOfWeek?: number;
  /** IANA 时区 */
  timezone: string;
}

/** 后端 execution_record 行 */
export interface BackendExecution {
  id: number;
  ruleId?: number;
  ruleName: string;
  durationMs: number | null;
  status: string;
  detail: string;
  startedAt: string;
}

function formatTime(ts: string | null): string {
  if (!ts) return "—";
  return ts.slice(5, 16).replace("T", " ");
}

/** 日程摘要(列表展示):「每日 09:00(Asia/Shanghai)」/「每周五 09:00」。 */
function describeSchedule(scheduleJson: string | null): string | null {
  if (!scheduleJson) return null;
  try {
    const s = JSON.parse(scheduleJson) as { frequency?: string; localTime?: string; dayOfWeek?: number; timezone?: string };
    if (!s.frequency || !s.localTime) return null;
    const week = ["", "一", "二", "三", "四", "五", "六", "日"];
    const base = s.frequency === "weekly" && s.dayOfWeek
      ? `每周${week[s.dayOfWeek] ?? ""} ${s.localTime}`
      : `每日 ${s.localTime}`;
    return s.timezone ? `${base}（${s.timezone}）` : base;
  } catch {
    return null;
  }
}

function toRule(r: BackendRule): AutomationRule {
  const scheduleText = describeSchedule(r.schedule);
  return {
    id: r.id,
    name: r.name,
    trigger: scheduleText ?? r.triggerLabel ?? r.triggerType,
    action: describeAction(r.actionJson),
    enabled: r.enabled,
    lastRun: formatTime(r.lastRunAt),
    status: r.status === "error" ? "error" : r.enabled ? "active" : "paused",
    nextRun: r.nextRunAt ? formatTime(r.nextRunAt) : undefined,
    needsConfig: r.configurationStatus === "needs_config",
  };
}

function describeAction(actionJson: string): string {
  try {
    const action = JSON.parse(actionJson) as { type?: string; sql?: string; prompt?: string };
    if (action.type === "agent" && action.prompt) {
      const oneLine = action.prompt.replace(/\s+/g, " ");
      return "🤖 " + (oneLine.length > 60 ? oneLine.slice(0, 60) + "…" : oneLine);
    }
    if (action.type === "sql" && action.sql) {
      const oneLine = action.sql.replace(/\s+/g, " ");
      return oneLine.length > 60 ? oneLine.slice(0, 60) + "…" : oneLine;
    }
  } catch {
    /* fall through */
  }
  return "SQL 查询";
}

function toExecution(e: BackendExecution): ExecutionRecord {
  const detail = e.detail ?? "";
  return {
    id: e.id,
    ruleId: e.ruleId,
    ruleName: e.ruleName,
    time: formatTime(e.startedAt),
    duration: e.durationMs != null ? `${(e.durationMs / 1000).toFixed(1)}s` : "—",
    status: e.status === "success" ? "success" : "failed",
    // 全文保留(2026-09-20,M0-04):此前截为 120 字,「成功」之后拿不到报告。
    // 列表用 detailSummary 展示摘要,详情面板读全文。
    detail,
    detailSummary: summarize(detail, 120),
  };
}

/** 摘要:取首个非空行,超长截断加省略号(仅列表展示用)。 */
function summarize(detail: string, max: number): string {
  const firstLine = detail.split("\n").map((l) => l.trim()).find((l) => l.length > 0) ?? "";
  return firstLine.length > max ? firstLine.slice(0, max) + "…" : firstLine;
}

/**
 * 自动任务后端接入层(USE_BACKEND 开关):
 * - listRules      → GET    /api/automations            → AutomationRule[]
 * - createRule     → POST   /api/automations            → AutomationRule
 * - toggleRule     → POST   /api/automations/{id}/toggle
 * - deleteRule     → DELETE /api/automations/{id}
 * - runRule        → POST   /api/automations/{id}/run   → ExecutionRecord
 * - listExecutions → GET    /api/automations/executions → ExecutionRecord[]
 */
export const automationsApi = {
  async listRules(): Promise<AutomationRule[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendRule[]>("/automations");
    return items.map(toRule);
  },

  async createRule(input: {
    name: string;
    triggerType: string;
    /** 动作类型:"sql"(默认)或 "agent" */
    actionType?: string;
    sql?: string;
    /** actionType="agent" 时的自然语言指令 */
    prompt?: string;
    /** daily/weekly 必填:日程 JSON 字符串(M4-01,{frequency,localTime,dayOfWeek,timezone}) */
    schedule?: string;
  }): Promise<AutomationRule> {
    const item = await requestJson<BackendRule>("/automations", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return toRule(item);
  },

  /** 日程预览(M4-01):服务端计算未来 3 次计划点(保存前展示准确时间)。 */
  async previewSchedule(schedule: ScheduleSpec, triggerType: string): Promise<string[]> {
    const items = await requestJson<string[]>("/automations/schedule-preview", {
      method: "POST",
      body: JSON.stringify({ triggerType, schedule: JSON.stringify(schedule) }),
    });
    return items;
  },

  async toggleRule(id: number): Promise<void> {
    await requestJson(`/automations/${id}/toggle`, { method: "POST" });
  },

  async deleteRule(id: number): Promise<void> {
    await requestJson(`/automations/${id}`, { method: "DELETE" });
  },

  async runRule(id: number): Promise<ExecutionRecord> {
    // 手动运行是**同步等待**执行完成的调用:agent 动作实测可跑 105s+,
    // 后端(automation→agent)读超时 300s。前端若用默认 30s 会先断流报错,
    // 而后端其实执行成功并落了成功记录——「明明成功却提示失败」(实测)。
    // 给 6 分钟覆盖后端 5 分钟上限 + 网络余量。
    const item = await requestJson<BackendExecution>(`/automations/${id}/run`, {
      method: "POST",
      signal: defaultTimeoutSignal(6 * 60_000),
    });
    return toExecution(item);
  },

  async listExecutions(limit = 50): Promise<ExecutionRecord[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendExecution[]>(`/automations/executions?limit=${limit}`);
    return items.map(toExecution);
  },
};

export { toRule, toExecution };
