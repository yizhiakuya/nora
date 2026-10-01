'use client';

import { useEffect, useState } from "react";
import { FileText, ChevronRight } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import MarkdownContent from "@/components/shared/MarkdownContent";
import { Button } from "@/components/ui/button";
import type { ExecutionRecord } from "@/types";
import { automationsApi } from "@/lib/services/automationsApi";
import { executionStatusMeta } from "@/lib/executionStatus";

/**
 * 「最近任务结果」(M1-02,2026-09-20;B1 命名修正 2026-09-27)。
 *
 * 数据源为 automation execution_record(最近 5 条,**含失败/取消/错过计划**)。
 * 评审报告 B1 指出「成果」这个名字与实际内容不符——这里展示的是自动任务
 * 执行记录,成功与否都列出;真正的「已保存成果」(对话保存的文件)在
 * 资料页。命名与空态文案对齐实际内容,避免用户以为这里只有可用的成果。
 *
 * 状态展示(B5):改用共享映射(lib/executionStatus.ts),与执行历史/
 * 已保存成果同一份——同一条记录在各入口状态一致。
 */
export function RecentResults() {
  const [records, setRecords] = useState<ExecutionRecord[]>([]);
  const [viewing, setViewing] = useState<ExecutionRecord | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const items = await automationsApi.listExecutions(5);
        if (!cancelled) setRecords(items);
      } catch {
        /* 后端不可达:空态 */
      } finally {
        if (!cancelled) setLoaded(true);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (!loaded) return null;

  if (records.length === 0) {
    return (
      <div className="bg-card border border-dashed border-border rounded-xl py-8 text-center space-y-1">
        <FileText className="w-6 h-6 mx-auto text-muted-foreground/40" />
        <div className="text-xs text-muted-foreground">还没有任务执行记录</div>
        <div className="text-[11px] text-muted-foreground/70">
          在「助手」中完成任务,或到「任务」页创建定期任务,执行结果会出现在这里。
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
              className="w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-muted/40 transition-colors cursor-pointer"
            >
              <Icon className={`w-3.5 h-3.5 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
              <span className="text-xs font-medium text-foreground truncate min-w-0 flex-1">{rec.ruleName}</span>
              <span className={`text-[10px] shrink-0 ${meta.cls}`}>{meta.label}</span>
              <span className="text-[10px] text-muted-foreground tabular-nums shrink-0">{rec.time}</span>
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
