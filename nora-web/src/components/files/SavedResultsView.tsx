'use client';

import { useEffect, useState } from "react";
import { CheckCircle2, XCircle, FileText, ChevronRight, Loader2 } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Button } from "@/components/ui/button";
import type { ExecutionRecord } from "@/types";
import { automationsApi } from "@/lib/services/automationsApi";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 「已保存成果」视图(M1-03,2026-09-20,方案 §4.2):
 * 任务执行产出的完整结果(成功/失败),点击打开全文。
 *
 * 说明:当前成果以 execution_record.detail 为权威存储(任务执行结果),
 * 不复制到第三个存储;对话中的 `nora-artifacts` 画廊仍在会话内呈现。
 * 删除任务不删除这里的历史结果(execution_record 独立于规则)。
 */
export function SavedResultsView() {
  const [records, setRecords] = useState<ExecutionRecord[]>([]);
  const [viewing, setViewing] = useState<ExecutionRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!USE_BACKEND) {
        setLoading(false);
        return;
      }
      try {
        const items = await automationsApi.listExecutions(50);
        if (!cancelled) setRecords(items);
      } catch {
        if (!cancelled) setFailed(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div className="bg-card border border-border rounded-xl py-16 text-center text-xs text-muted-foreground flex items-center justify-center gap-2">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> 加载成果…
      </div>
    );
  }

  if (failed) {
    return (
      <div className="bg-card border border-border rounded-xl py-16 text-center space-y-1">
        <div className="text-xs text-muted-foreground">成果列表加载失败</div>
        <div className="text-[11px] text-muted-foreground/70">请检查后端服务后刷新重试</div>
      </div>
    );
  }

  if (records.length === 0) {
    return (
      <div className="bg-card border border-dashed border-border rounded-xl py-16 text-center space-y-1">
        <FileText className="w-6 h-6 mx-auto text-muted-foreground/40" />
        <div className="text-xs text-muted-foreground">还没有已保存的成果</div>
        <div className="text-[11px] text-muted-foreground/70">
          在「助手」中完成任务,或到「任务」页运行定期任务,结果会出现在这里。
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="bg-card border border-border rounded-xl divide-y divide-gray-100 dark:divide-gray-800 overflow-hidden">
        {records.map((rec) => (
          <button
            key={rec.id}
            type="button"
            onClick={() => setViewing(rec)}
            className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-muted/40 transition-colors cursor-pointer"
          >
            {rec.status === "success"
              ? <CheckCircle2 className="w-4 h-4 shrink-0 text-green-600 dark:text-green-400" />
              : <XCircle className="w-4 h-4 shrink-0 text-red-600 dark:text-red-400" />}
            <div className="min-w-0 flex-1">
              <div className="text-xs font-bold text-foreground truncate">{rec.ruleName}</div>
              <div className="text-[11px] text-muted-foreground truncate mt-0.5">
                {rec.detailSummary ?? rec.detail}
              </div>
            </div>
            <div className="text-right shrink-0">
              <div className="text-[10px] text-muted-foreground tabular-nums">{rec.time}</div>
              <div className="text-[10px] text-muted-foreground tabular-nums">{rec.duration}</div>
            </div>
            <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/60 shrink-0" />
          </button>
        ))}
      </div>

      <Modal
        isOpen={viewing != null}
        onClose={() => setViewing(null)}
        title={viewing ? `${viewing.ruleName} · ${viewing.status === "success" ? "成功" : "失败"}` : ""}
        width="w-[94%] sm:w-[720px]"
        footer={viewing && (
          <>
            <Button variant="outline" size="sm" onClick={() => { void navigator.clipboard.writeText(viewing.detail); }}>
              复制全文
            </Button>
            <Button size="sm" onClick={() => setViewing(null)}>关闭</Button>
          </>
        )}
      >
        {viewing && (
          <div className="space-y-3">
            <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
              <span>时间:{viewing.time}</span>
              <span>耗时:{viewing.duration}</span>
              <span>状态:{viewing.status === "success" ? "成功" : "失败"}</span>
            </div>
            <pre className="max-h-[420px] overflow-auto rounded-lg border border-border bg-muted/40 p-3 text-xs whitespace-pre-wrap break-words font-mono">
              {viewing.detail || "（本次执行没有输出内容）"}
            </pre>
          </div>
        )}
      </Modal>
    </>
  );
}
