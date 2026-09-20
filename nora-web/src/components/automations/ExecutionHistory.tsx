'use client';

import { useState } from "react";
import { RotateCw, CheckCircle2, XCircle, Loader2, History, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import type { ExecutionRecord } from "@/types";
import { useAutomations } from "@/hooks/useAutomations";

const STATUS_META: Record<ExecutionRecord["status"], { icon: React.ElementType; cls: string; label: string }> = {
  success: { icon: CheckCircle2, cls: "text-green-600 dark:text-green-400", label: "成功" },
  failed:  { icon: XCircle,      cls: "text-red-600 dark:text-red-400",     label: "失败" },
  running: { icon: Loader2,      cls: "text-blue-600 dark:text-blue-400",   label: "执行中" },
};

/**
 * 执行历史(M0-04,2026-09-20):
 * 列表只展示摘要(首行,≤120 字),点击行打开**全文详情**——此前详情被
 * 截为 120 字且没有查看入口,「成功」之后拿不到报告(审查报告 B04)。
 * detail 字段本身已是全文(automationsApi.toExecution),详情面板直接渲染。
 */
export function ExecutionHistory() {
  const [retrying, setRetrying] = useState<number | null>(null);
  const [viewing, setViewing] = useState<ExecutionRecord | null>(null);
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
    <>
      <div className="bg-card border border-border rounded-xl overflow-hidden">
        <div className="px-4 py-2.5 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center gap-1.5">
          <History className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-bold text-foreground">执行历史</span>
          <span className="text-[10px] text-muted-foreground ml-auto">{records.length} 条 · 点击查看完整结果</span>
        </div>
        <div className="divide-y divide-gray-100 dark:divide-gray-800">
          {records.map((rec) => {
            const meta = STATUS_META[rec.status];
            const Icon = meta.icon;
            return (
              <div
                key={rec.id}
                className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-muted/40 transition-colors"
                onClick={() => setViewing(rec)}
                title="查看完整结果"
              >
                <Icon className={`w-4 h-4 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-foreground">{rec.ruleName}</span>
                    <span className={`text-[9px] font-bold ${meta.cls}`}>{meta.label}</span>
                  </div>
                  {/* 列表摘要:首行截断;全文在详情面板 */}
                  <div className="text-[11px] text-muted-foreground truncate mt-0.5">
                    {rec.detailSummary ?? rec.detail}
                  </div>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-[10px] text-muted-foreground tabular-nums">{rec.time}</div>
                  <div className="text-[10px] text-muted-foreground tabular-nums">{rec.duration}</div>
                </div>
                {rec.status === "failed" && (
                  <Button
                    variant="outline" size="sm" className="h-6 text-[10px] px-2 shrink-0"
                    onClick={(e) => { e.stopPropagation(); retry(rec); }}
                    disabled={retrying === rec.id}
                  >
                    {retrying === rec.id ? <Loader2 className="w-2.5 h-2.5 animate-spin" /> : <RotateCw className="w-2.5 h-2.5" />}
                    重试
                  </Button>
                )}
                <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/60 shrink-0" />
              </div>
            );
          })}
        </div>
      </div>

      {/* 完整结果详情:全文渲染(不截断),可复制 */}
      <Modal
        isOpen={viewing != null}
        onClose={() => setViewing(null)}
        title={viewing ? `${viewing.ruleName} · ${STATUS_META[viewing.status].label}` : ""}
        width="w-[94%] sm:w-[720px]"
        footer={viewing && (
          <>
            <Button
              variant="outline" size="sm"
              onClick={() => { void navigator.clipboard.writeText(viewing.detail); }}
            >
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
              <span>状态:{STATUS_META[viewing.status].label}</span>
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
