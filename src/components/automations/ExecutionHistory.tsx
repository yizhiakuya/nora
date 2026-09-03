'use client';

import { useState } from "react";
import { toast } from "sonner";
import { RotateCw, CheckCircle2, XCircle, Loader2, History } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MOCK_EXECUTIONS, ExecutionRecord } from "@/lib/devData";

const STATUS_META: Record<ExecutionRecord["status"], { icon: React.ElementType; cls: string; label: string }> = {
  success: { icon: CheckCircle2, cls: "text-green-600 dark:text-green-400", label: "成功" },
  failed:  { icon: XCircle,      cls: "text-red-600 dark:text-red-400",     label: "失败" },
  running: { icon: Loader2,      cls: "text-blue-600 dark:text-blue-400",   label: "执行中" },
};

export function ExecutionHistory() {
  const [retrying, setRetrying] = useState<number | null>(null);

  const retry = (record: ExecutionRecord) => {
    setRetrying(record.id);
    setTimeout(() => {
      setRetrying(null);
      toast.success(`「${record.ruleName}」已重新触发`);
    }, 800);
  };

  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl overflow-hidden">
      <div className="px-4 py-2.5 border-b border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-950/50 flex items-center gap-1.5">
        <History className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" />
        <span className="text-xs font-bold text-gray-700 dark:text-gray-200">执行历史</span>
        <span className="text-[10px] text-gray-400 dark:text-gray-500 ml-auto">{MOCK_EXECUTIONS.length} 条 · 最近 24h</span>
      </div>
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {MOCK_EXECUTIONS.map((rec) => {
          const meta = STATUS_META[rec.status];
          const Icon = meta.icon;
          return (
            <div key={rec.id} className="flex items-center gap-3 px-4 py-3">
              <Icon className={`w-4 h-4 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-gray-800 dark:text-gray-100">{rec.ruleName}</span>
                  <span className={`text-[9px] font-bold ${meta.cls}`}>{meta.label}</span>
                </div>
                <div className="text-[11px] text-gray-500 dark:text-gray-400 truncate mt-0.5">{rec.detail}</div>
              </div>
              <div className="text-right shrink-0">
                <div className="text-[10px] text-gray-400 dark:text-gray-500 tabular-nums">{rec.time}</div>
                <div className="text-[10px] text-gray-400 dark:text-gray-500 tabular-nums">{rec.duration}</div>
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
