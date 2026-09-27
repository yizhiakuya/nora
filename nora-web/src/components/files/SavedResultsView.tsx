'use client';

import { useEffect, useState } from "react";
import { FileText, ChevronRight, Loader2 } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import MarkdownContent from "@/components/shared/MarkdownContent";
import { Button } from "@/components/ui/button";
import type { ExecutionRecord } from "@/types";
import { automationsApi } from "@/lib/services/automationsApi";
import { USE_BACKEND } from "@/lib/api/client";
import { executionStatusMeta } from "@/lib/executionStatus";

/**
 * 「任务结果」视图(M1-03,2026-09-20;B1 命名修正 2026-09-27):
 * 自动任务执行产出的完整结果(成功/失败都列出),点击打开全文。
 *
 * B1(评审报告):此前叫「已保存成果」,但内容是 execution_record 执行记录
 * (含取消/结果未知/错过计划)——与用户心智中「我保存的成果」不符,且
 * 对话「保存为文件」的报告不在这里出现。命名与空态文案改为实际内容,
 * 并指引真正的保存位置(工作区/知识库);状态展示用共享映射(B5)。
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
        <div className="text-xs text-muted-foreground">还没有任务执行结果</div>
        <div className="text-[11px] text-muted-foreground/70">
          自动任务的结果会出现在这里;对话中保存的报告在「工作区」文件夹,知识库文档在「长期知识」。
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="bg-card border border-border rounded-xl divide-y divide-gray-100 dark:divide-gray-800 overflow-hidden">
        {records.map((rec) => {
          const meta = executionStatusMeta(rec.status);
          const Icon = meta.icon;
          return (
            <button
              key={rec.id}
              type="button"
              onClick={() => setViewing(rec)}
              title={meta.actionHint ?? undefined}
              className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-muted/40 transition-colors cursor-pointer"
            >
              <Icon className={`w-4 h-4 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-foreground truncate">{rec.ruleName}</span>
                  <span className={`text-[9px] font-bold shrink-0 ${meta.cls}`}>{meta.label}</span>
                </div>
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
          );
        })}
      </div>

      <Modal
        isOpen={viewing != null}
        onClose={() => setViewing(null)}
        title={viewing ? `${viewing.ruleName} · ${executionStatusMeta(viewing.status).label}` : ""}
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
              <span>状态:{executionStatusMeta(viewing.status).label}</span>
            </div>
            <div className="max-h-[420px] overflow-auto rounded-lg border border-border bg-muted/40 p-3 text-xs leading-relaxed">
              <MarkdownContent className="prose-sm [&_pre]:whitespace-pre-wrap [&_pre]:break-words">{viewing.detail || "（本次执行没有输出内容）"}</MarkdownContent>
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}
