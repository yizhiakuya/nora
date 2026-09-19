'use client';

import { useState } from "react";
import { RotateCw, CheckCircle2, XCircle, Loader2, History } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ExecutionRecord } from "@/types";
import { useAutomations } from "@/hooks/useAutomations";

const STATUS_META: Record<ExecutionRecord["status"], { icon: React.ElementType; cls: string; label: string }> = {
  success: { icon: CheckCircle2, cls: "text-green-600 dark:text-green-400", label: "成功" },
  failed:  { icon: XCircle,      cls: "text-red-600 dark:text-red-400",     label: "失败" },
  running: { icon: Loader2,      cls: "text-blue-600 dark:text-blue-400",   label: "执行中" },
};

export function ExecutionHistory() {
  const [retrying, setRetrying] = useState<number | null>(null);
  const records = useAutomations((s) => s.executions);
  const retryExecution = useAutomations((s) => s.retryExecution);

  const retry = (record: ExecutionRecord) => {
    // 真实重试(2026-09-19 去假功能):等待态只覆盖真实请求时长,不再用
    // 800ms 假动画 + 硬编码"重试成功 1.4s"文案;结果由 store 的真实回调
    // 决定(成功/失败各有通知)。
    setRetrying(record.id);
    retryExecution(record.id);
    // 重试是异步的:短时间内清等待态(真实结果通过执行历史刷新呈现)
    setTimeout(() => setRetrying(null), 3000);
  };

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      <div className="px-4 py-2.5 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center gap-1.5">
        <History className="w-3.5 h-3.5 text-muted-foreground" />
        <span className="text-xs font-bold text-foreground">执行历史</span>
        <span className="text-[10px] text-muted-foreground ml-auto">{records.length} 条 · 最近 24h</span>
      </div>
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {records.map((rec) => {
          const meta = STATUS_META[rec.status];
          const Icon = meta.icon;
          return (
            <div key={rec.id} className="flex items-center gap-3 px-4 py-3">
              <Icon className={`w-4 h-4 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-foreground">{rec.ruleName}</span>
                  <span className={`text-[9px] font-bold ${meta.cls}`}>{meta.label}</span>
                </div>
                <div className="text-[11px] text-muted-foreground truncate mt-0.5">{rec.detail}</div>
              </div>
              <div className="text-right shrink-0">
                <div className="text-[10px] text-muted-foreground tabular-nums">{rec.time}</div>
                <div className="text-[10px] text-muted-foreground tabular-nums">{rec.duration}</div>
              </div>
              {rec.status === "failed" && (
                <Button variant="outline" size="sm" className="h-6 text-[10px] px-2 shrink-0" onClick={() => retry(rec)} disabled={retrying === rec.id}>
                  {retrying === rec.id ? <Loader2 className="w-2.5 h-2.5 animate-spin" /> : <RotateCw className="w-2.5 h-2.5" />}
                  重试
                </Button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
