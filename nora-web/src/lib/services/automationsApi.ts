import { requestJson, USE_BACKEND } from "@/lib/api/client";
import type { AutomationRule, ExecutionRecord } from "@/types";

/** 后端 automation_rule 行 */
export interface BackendRule {
  id: number;
  name: string;
  triggerType: string;
  triggerLabel: string;
  actionJson: string;
  enabled: boolean;
  status: string;
  lastRunAt: string | null;
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

function toRule(r: BackendRule): AutomationRule {
  return {
    id: r.id,
    name: r.name,
    trigger: r.triggerLabel ?? r.triggerType,
    action: describeAction(r.actionJson),
    enabled: r.enabled,
    lastRun: formatTime(r.lastRunAt),
    status: r.status === "error" ? "error" : r.enabled ? "active" : "paused",
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
  return {
    id: e.id,
    ruleId: e.ruleId,
    ruleName: e.ruleName,
    time: formatTime(e.startedAt),
    duration: e.durationMs != null ? `${(e.durationMs / 1000).toFixed(1)}s` : "—",
    status: e.status === "success" ? "success" : "failed",
    detail: (e.detail ?? "").slice(0, 120),
  };
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
  }): Promise<AutomationRule> {
    const item = await requestJson<BackendRule>("/automations", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return toRule(item);
  },

  async toggleRule(id: number): Promise<void> {
    await requestJson(`/automations/${id}/toggle`, { method: "POST" });
  },

  async deleteRule(id: number): Promise<void> {
    await requestJson(`/automations/${id}`, { method: "DELETE" });
  },

  async runRule(id: number): Promise<ExecutionRecord> {
    const item = await requestJson<BackendExecution>(`/automations/${id}/run`, { method: "POST" });
    return toExecution(item);
  },

  async listExecutions(limit = 50): Promise<ExecutionRecord[]> {
    if (!USE_BACKEND) return [];
    const items = await requestJson<BackendExecution[]>(`/automations/executions?limit=${limit}`);
    return items.map(toExecution);
  },
};

export { toRule, toExecution };
